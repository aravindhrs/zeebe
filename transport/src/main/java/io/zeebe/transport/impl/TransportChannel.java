/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.transport.impl;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.zeebe.dispatcher.FragmentHandler;
import io.zeebe.dispatcher.impl.log.DataFrameDescriptor;
import io.zeebe.transport.RemoteAddress;
import io.zeebe.util.allocation.AllocatedBuffer;
import io.zeebe.util.allocation.BufferAllocators;
import org.agrona.DirectBuffer;
import org.agrona.LangUtil;
import org.agrona.concurrent.UnsafeBuffer;

public class TransportChannel
{
    private static final AtomicIntegerFieldUpdater<TransportChannel> STATE_FIELD = AtomicIntegerFieldUpdater.newUpdater(TransportChannel.class, "state");

    private static final int CLOSED = 1;
    private static final int CONNECTING = 2;
    private static final int CONNECTED = 3;

    @SuppressWarnings("unused") // used through STATE_FIELD
    private volatile int state = CLOSED;

    private final RemoteAddress remoteAddress;
    private final AllocatedBuffer allocatedBuffer;
    private final ByteBuffer channelReadBuffer;
    private final UnsafeBuffer channelReadBufferView;

    private final ChannelLifecycleListener listener;
    private final FragmentHandler readHandler;

    private SocketChannel media;
    private CompletableFuture<Void> openFuture;

    private List<SelectionKey> registeredKeys = Collections.synchronizedList(new ArrayList<>());

    public TransportChannel(
            ChannelLifecycleListener listener,
            RemoteAddress remoteAddress,
            int maxMessageSize,
            FragmentHandler readHandler)
    {
        this.listener = listener;
        this.remoteAddress = remoteAddress;
        this.readHandler = readHandler;
        this.allocatedBuffer = BufferAllocators.allocateDirect(2 * maxMessageSize);
        this.channelReadBuffer = allocatedBuffer.getRawBuffer();
        this.channelReadBufferView = new UnsafeBuffer(channelReadBuffer);
    }

    public TransportChannel(
            ChannelLifecycleListener listener,
            RemoteAddress remoteAddress,
            int maxMessageSize,
            FragmentHandler readHandler,
            FragmentHandler sendFailureHandler,
            SocketChannel media)
    {
        this(listener, remoteAddress, maxMessageSize, readHandler);
        this.media = media;
        STATE_FIELD.set(this, CONNECTED);
    }

    public int receive()
    {
        int workCount = 0;

        if (mediaReceive(media, channelReadBuffer) < 0)
        {
            doClose();
            return workCount;
        }

        final int available = channelReadBuffer.position();

        int remaining = available;
        int offset = 0;

        while (remaining >= DataFrameDescriptor.HEADER_LENGTH)
        {
            workCount += 1;

            final int msgLength = channelReadBufferView.getInt(DataFrameDescriptor.lengthOffset(offset));
            final int msgOffset = DataFrameDescriptor.messageOffset(offset);
            final int frameLength = DataFrameDescriptor.alignedLength(msgLength);

            if (remaining < frameLength)
            {
                break;
            }
            else
            {
                final boolean handled = handleMessage(channelReadBufferView, msgOffset, msgLength);

                if (handled)
                {
                    remaining -= frameLength;
                    offset += frameLength;
                }
                else
                {
                    break;
                }
            }
        }

        if (offset > 0)
        {
            channelReadBuffer.limit(available);
            channelReadBuffer.position(offset);
            channelReadBuffer.compact();
        }

        return workCount;
    }

    private boolean handleMessage(DirectBuffer buffer, int msgOffset, int msgLength)
    {
        try
        {
            return readHandler.onFragment(buffer, msgOffset, msgLength, getStreamId(), false) != FragmentHandler.POSTPONE_FRAGMENT_RESULT;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return true;
        }
    }

    private int mediaReceive(SocketChannel media, ByteBuffer receiveBuffer)
    {
        int bytesReceived = -2;

        try
        {
            bytesReceived = media.read(receiveBuffer);
        }
        catch (IOException e)
        {
            doClose();
        }

        return bytesReceived;
    }

    public int write(ByteBuffer buffer)
    {
        int bytesWritten = -1;

        try
        {
            bytesWritten = media.write(buffer);
        }
        catch (IOException e)
        {
            doClose();
        }

        return bytesWritten;
    }

    public int getStreamId()
    {
        return remoteAddress.getStreamId();
    }


    public void registerSelector(Selector selector, int ops)
    {
        try
        {
            final SelectionKey key = media.register(selector, ops);
            key.attach(this);
            registeredKeys.add(key);
        }
        catch (ClosedChannelException e)
        {
            LangUtil.rethrowUnchecked(e);
        }
    }

    public void removeSelector(Selector selector)
    {
        final SelectionKey key = media.keyFor(selector);
        if (key != null)
        {
            key.cancel();
            registeredKeys.remove(key);
        }
    }

    public boolean beginConnect(CompletableFuture<Void> openFuture)
    {
        if (STATE_FIELD.compareAndSet(this, CLOSED, CONNECTING))
        {
            this.openFuture = openFuture;

            try
            {
                media = SocketChannel.open();
                media.setOption(StandardSocketOptions.TCP_NODELAY, true);
                media.configureBlocking(false);
                media.connect(remoteAddress.getAddress().toInetSocketAddress());
                return true;
            }
            catch (Exception e)
            {
                e.printStackTrace();
                doClose();
                openFuture.completeExceptionally(e);
                return false;
            }
        }
        else
        {
            return false;
        }
    }

    public void finishConnect()
    {
        if (STATE_FIELD.compareAndSet(this, CONNECTING, CONNECTED))
        {
            try
            {
                media.finishConnect();
                openFuture.complete(null);
                listener.onChannelConnected(this);
            }
            catch (IOException e)
            {
                e.printStackTrace();
                openFuture.completeExceptionally(e);
                doClose();
            }
        }
        else
        {
            openFuture.completeExceptionally(new IllegalStateException("Channel is not connecting"));
        }
    }

    private void doClose()
    {
        try
        {
            if (media != null)
            {

                try
                {
                    synchronized (registeredKeys)
                    {
                        registeredKeys.forEach(k -> k.cancel());
                        registeredKeys.clear();
                    }
                }
                finally
                {
                    media.close();
                }
            }

            allocatedBuffer.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            // ignore
        }
        finally
        {
            // invoke listener only once and only if connected was invoked as well
            if (STATE_FIELD.getAndSet(this, CLOSED) == CONNECTED)
            {
                listener.onChannelDisconnected(this);
            }
        }
    }

    public RemoteAddress getRemoteAddress()
    {
        return remoteAddress;
    }

    public void shutdownInput()
    {
        try
        {
            media.shutdownInput();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public interface ChannelLifecycleListener
    {
        void onChannelConnected(TransportChannel transportChannelImpl);

        void onChannelDisconnected(TransportChannel transportChannelImpl);
    }

    public void close()
    {
        doClose();
    }

}
