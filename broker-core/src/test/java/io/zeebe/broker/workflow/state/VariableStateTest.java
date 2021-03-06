/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.workflow.state;

import static io.zeebe.util.buffer.BufferUtil.bufferAsString;
import static io.zeebe.util.buffer.BufferUtil.wrapString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.zeebe.broker.logstreams.processor.TypedRecord;
import io.zeebe.broker.logstreams.state.ZeebeState;
import io.zeebe.broker.util.ZeebeStateRule;
import io.zeebe.broker.workflow.state.VariablesState.VariableListener;
import io.zeebe.protocol.impl.record.RecordMetadata;
import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord;
import io.zeebe.protocol.intent.WorkflowInstanceIntent;
import io.zeebe.test.util.MsgPackUtil;
import io.zeebe.util.buffer.BufferUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.agrona.DirectBuffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class VariableStateTest {

  @Rule public ZeebeStateRule stateRule = new ZeebeStateRule();

  private ElementInstanceState elementInstanceState;
  private VariablesState variablesState;

  @Before
  public void setUp() {
    final ZeebeState zeebeState = stateRule.getZeebeState();
    elementInstanceState = zeebeState.getWorkflowState().getElementInstanceState();
    variablesState = elementInstanceState.getVariablesState();
  }

  @Test
  public void shouldCollectVariablesAsDocument() {
    // given
    final long scopeKey = 1;
    declareScope(scopeKey);

    final DirectBuffer var1Value = MsgPackUtil.asMsgPack("a", 1);
    variablesState.setVariableLocal(scopeKey, BufferUtil.wrapString("var1"), var1Value);

    final DirectBuffer var2Value = MsgPackUtil.asMsgPack("x", 10);
    variablesState.setVariableLocal(scopeKey, BufferUtil.wrapString("var2"), var2Value);

    // when
    final DirectBuffer variablesDocument = variablesState.getVariablesAsDocument(scopeKey);

    // then
    MsgPackUtil.assertEquality(variablesDocument, "{'var1': {'a': 1}, 'var2': {'x': 10}}");
  }

  @Test
  public void shouldCollectNoVariablesAsEmptyDocument() {
    // given
    final long scopeKey = 1;
    declareScope(scopeKey);

    // when
    final DirectBuffer variablesDocument = variablesState.getVariablesAsDocument(scopeKey);

    // then
    MsgPackUtil.assertEquality(variablesDocument, "{}");
  }

  @Test
  public void shouldCollectVariablesFromMultipleScopes() {
    // given
    final long grandparent = 1;
    final long parent = 2;
    final long child = 3;
    declareScope(grandparent);
    declareScope(grandparent, parent);
    declareScope(parent, child);

    variablesState.setVariableLocal(
        grandparent, BufferUtil.wrapString("a"), MsgPackUtil.asMsgPack("1"));
    variablesState.setVariableLocal(parent, BufferUtil.wrapString("b"), MsgPackUtil.asMsgPack("2"));
    variablesState.setVariableLocal(child, BufferUtil.wrapString("c"), MsgPackUtil.asMsgPack("3"));

    // when
    final DirectBuffer variablesDocument = variablesState.getVariablesAsDocument(child);

    // then
    MsgPackUtil.assertEquality(variablesDocument, "{'a': 1, 'b': 2, 'c': 3}");
  }

  @Test
  public void shouldNotCollectHiddenVariables() {
    // given
    final long parent = 1;
    final long child = 2;
    declareScope(parent);
    declareScope(parent, child);

    variablesState.setVariableLocal(parent, BufferUtil.wrapString("a"), MsgPackUtil.asMsgPack("1"));
    variablesState.setVariableLocal(parent, BufferUtil.wrapString("b"), MsgPackUtil.asMsgPack("2"));
    variablesState.setVariableLocal(child, BufferUtil.wrapString("b"), MsgPackUtil.asMsgPack("3"));

    // when
    final DirectBuffer variablesDocument = variablesState.getVariablesAsDocument(child);

    // then
    MsgPackUtil.assertEquality(variablesDocument, "{'a': 1, 'b': 3}");
  }

  @Test
  public void shouldNotCollectVariablesFromChildScope() {
    // given
    final long parent = 1;
    final long child = 2;
    declareScope(parent);
    declareScope(parent, child);

    variablesState.setVariableLocal(parent, BufferUtil.wrapString("a"), MsgPackUtil.asMsgPack("1"));
    variablesState.setVariableLocal(child, BufferUtil.wrapString("b"), MsgPackUtil.asMsgPack("3"));

    // when
    final DirectBuffer variablesDocument = variablesState.getVariablesAsDocument(parent);

    // then
    MsgPackUtil.assertEquality(variablesDocument, "{'a': 1}");
  }

  @Test
  public void shouldNotCollectVariablesInSiblingScope() {
    // given
    final long parent = 1;
    final long child1 = 2;
    final long child2 = 3;
    declareScope(parent);
    declareScope(parent, child1);
    declareScope(parent, child2);

    variablesState.setVariableLocal(parent, BufferUtil.wrapString("a"), MsgPackUtil.asMsgPack("1"));
    variablesState.setVariableLocal(child1, BufferUtil.wrapString("b"), MsgPackUtil.asMsgPack("2"));
    variablesState.setVariableLocal(child2, BufferUtil.wrapString("c"), MsgPackUtil.asMsgPack("3"));

    // when
    final DirectBuffer variablesDocument = variablesState.getVariablesAsDocument(child1);

    // then
    MsgPackUtil.assertEquality(variablesDocument, "{'a': 1, 'b': 2}");
  }

  @Test
  public void shouldCollectLocalVariables() {
    // given
    final long parent = 1;
    final long child = 2;
    declareScope(parent);
    declareScope(parent, child);

    variablesState.setVariableLocal(parent, BufferUtil.wrapString("a"), MsgPackUtil.asMsgPack("1"));
    variablesState.setVariableLocal(child, BufferUtil.wrapString("b"), MsgPackUtil.asMsgPack("3"));

    // then
    MsgPackUtil.assertEquality(variablesState.getVariablesLocalAsDocument(parent), "{'a': 1}");
    MsgPackUtil.assertEquality(variablesState.getVariablesLocalAsDocument(child), "{'b': 3}");
  }

  @Test
  public void shouldCollectVariablesByName() {
    // given
    final long scope = 1;
    declareScope(scope);

    variablesState.setVariableLocal(scope, BufferUtil.wrapString("a"), MsgPackUtil.asMsgPack("1"));
    variablesState.setVariableLocal(scope, BufferUtil.wrapString("b"), MsgPackUtil.asMsgPack("2"));
    variablesState.setVariableLocal(scope, BufferUtil.wrapString("c"), MsgPackUtil.asMsgPack("3"));

    // when
    final DirectBuffer variablesDocument =
        variablesState.getVariablesAsDocument(
            scope, Arrays.asList(BufferUtil.wrapString("a"), BufferUtil.wrapString("c")));

    // then
    MsgPackUtil.assertEquality(variablesDocument, "{'a': 1, 'c': 3}");
  }

  @Test
  public void shouldCollectVariablesByNameFromMultipleScopes() {
    // given
    final long grandparent = 1;
    final long parent = 2;
    final long child = 3;

    declareScope(grandparent);
    declareScope(grandparent, parent);
    declareScope(parent, child);

    variablesState.setVariableLocal(
        grandparent, BufferUtil.wrapString("a"), MsgPackUtil.asMsgPack("1"));
    variablesState.setVariableLocal(parent, BufferUtil.wrapString("b"), MsgPackUtil.asMsgPack("2"));
    variablesState.setVariableLocal(child, BufferUtil.wrapString("c"), MsgPackUtil.asMsgPack("3"));

    // when
    final DirectBuffer variablesDocument =
        variablesState.getVariablesAsDocument(
            child, Arrays.asList(BufferUtil.wrapString("a"), BufferUtil.wrapString("c")));

    // then
    MsgPackUtil.assertEquality(variablesDocument, "{'a': 1, 'c': 3}");
  }

  @Test
  public void shouldCollectOnlyExistingVariablesByName() {
    // given
    final long scope = 1;
    declareScope(scope);

    variablesState.setVariableLocal(scope, BufferUtil.wrapString("a"), MsgPackUtil.asMsgPack("1"));

    // when
    final DirectBuffer variablesDocument =
        variablesState.getVariablesAsDocument(
            scope, Arrays.asList(BufferUtil.wrapString("a"), BufferUtil.wrapString("c")));

    // then
    MsgPackUtil.assertEquality(variablesDocument, "{'a': 1}");
  }

  @Test
  public void shouldSetLocalVariablesFromDocument() {
    // given
    final long scope = 1;
    declareScope(scope);

    final DirectBuffer document = MsgPackUtil.asMsgPack(b -> b.put("a", 1).put("b", 2));

    // when
    variablesState.setVariablesLocalFromDocument(scope, document);

    // then
    final DirectBuffer varA = variablesState.getVariableLocal(scope, BufferUtil.wrapString("a"));
    MsgPackUtil.assertEquality(varA, "1");

    final DirectBuffer varB = variablesState.getVariableLocal(scope, BufferUtil.wrapString("b"));
    MsgPackUtil.assertEquality(varB, "2");
  }

  @Test
  public void shouldSetLocalVariablesFromDocumentInHierarchy() {
    // given
    final long parent = 1;
    final long child = 2;
    declareScope(parent);
    declareScope(parent, child);

    final DirectBuffer document = MsgPackUtil.asMsgPack(b -> b.put("a", 1).put("b", 2));

    // when
    variablesState.setVariablesLocalFromDocument(child, document);

    // then
    final DirectBuffer varA = variablesState.getVariableLocal(child, BufferUtil.wrapString("a"));
    MsgPackUtil.assertEquality(varA, "1");
    assertThat(variablesState.getVariableLocal(parent, BufferUtil.wrapString("a"))).isNull();

    final DirectBuffer varB = variablesState.getVariableLocal(child, BufferUtil.wrapString("b"));
    MsgPackUtil.assertEquality(varB, "2");
    assertThat(variablesState.getVariableLocal(parent, BufferUtil.wrapString("b"))).isNull();
  }

  @Test
  public void shouldSetLocalVariableFromDocumentAsObject() {
    // given
    final long scope = 1;
    declareScope(scope);

    final DirectBuffer document =
        MsgPackUtil.asMsgPack(b -> b.put("var", Collections.singletonMap("a", 1)));

    // when
    variablesState.setVariablesLocalFromDocument(scope, document);

    // then
    final DirectBuffer varA = variablesState.getVariableLocal(scope, BufferUtil.wrapString("var"));
    MsgPackUtil.assertEquality(varA, "{'a': 1}");
  }

  @Test
  public void shouldOverwriteLocalVariableFromDocument() {
    // given
    final long scope = 1;
    declareScope(scope);

    variablesState.setVariableLocal(scope, BufferUtil.wrapString("a"), MsgPackUtil.asMsgPack("1"));

    final DirectBuffer document = MsgPackUtil.asMsgPack("a", 2);

    // when
    variablesState.setVariablesLocalFromDocument(scope, document);

    // then
    final DirectBuffer varA = variablesState.getVariableLocal(scope, BufferUtil.wrapString("a"));
    MsgPackUtil.assertEquality(varA, "2");
  }

  @Test
  public void shouldGetNullForNonExistingVariable() {
    // given
    final long scope = 1;
    declareScope(scope);

    // when
    final DirectBuffer variableValue =
        variablesState.getVariableLocal(scope, BufferUtil.wrapString("a"));

    // then
    assertThat(variableValue).isNull();
  }

  @Test
  public void shouldRemoveAllVariablesForScope() {
    // given
    final long parent = 1;
    final long child = 2;
    declareScope(parent);
    declareScope(parent, child);

    variablesState.setVariableLocal(
        parent, BufferUtil.wrapString("parentVar1"), MsgPackUtil.asMsgPack("1"));
    variablesState.setVariableLocal(
        child, BufferUtil.wrapString("childVar1"), MsgPackUtil.asMsgPack("2"));
    variablesState.setVariableLocal(
        child, BufferUtil.wrapString("childVar2"), MsgPackUtil.asMsgPack("3"));

    // when
    variablesState.removeAllVariables(child);

    // then
    final DirectBuffer document = variablesState.getVariablesAsDocument(child);

    MsgPackUtil.assertEquality(document, "{'parentVar1': 1}");
  }

  @Test
  public void shouldRemoveScope() {
    // given
    final long parent = 1;
    final long child = 2;
    declareScope(parent);
    declareScope(parent, child);

    variablesState.setVariableLocal(
        parent, BufferUtil.wrapString("parentVar1"), MsgPackUtil.asMsgPack("1"));
    variablesState.setVariableLocal(
        child, BufferUtil.wrapString("childVar1"), MsgPackUtil.asMsgPack("2"));
    variablesState.setVariableLocal(
        child, BufferUtil.wrapString("childVar2"), MsgPackUtil.asMsgPack("3"));

    // when
    variablesState.removeScope(child);

    // then
    final DirectBuffer document = variablesState.getVariablesAsDocument(child);

    MsgPackUtil.assertEquality(document, "{}");
  }

  @Test
  public void shouldSetVariablesFromDocument() {
    // given
    final long grandparent = 1;
    final long parent = 2;
    final long child = 3;
    declareScope(grandparent);
    declareScope(grandparent, parent);
    declareScope(parent, child);

    variablesState.setVariableLocal(
        grandparent, BufferUtil.wrapString("a"), MsgPackUtil.asMsgPack("'should-overwrite-this'"));
    variablesState.setVariableLocal(
        parent, BufferUtil.wrapString("b"), MsgPackUtil.asMsgPack("'should-overwrite-this'"));
    variablesState.setVariableLocal(
        child, BufferUtil.wrapString("c"), MsgPackUtil.asMsgPack("'should-overwrite-this'"));

    final DirectBuffer document =
        MsgPackUtil.asMsgPack(b -> b.put("a", 1).put("b", 2).put("c", 3).put("d", 4));

    // when
    variablesState.setVariablesFromDocument(child, document);

    // then
    final DirectBuffer varA =
        variablesState.getVariableLocal(grandparent, BufferUtil.wrapString("a"));
    MsgPackUtil.assertEquality(varA, "1");

    final DirectBuffer varB = variablesState.getVariableLocal(parent, BufferUtil.wrapString("b"));
    MsgPackUtil.assertEquality(varB, "2");

    final DirectBuffer varC = variablesState.getVariableLocal(child, BufferUtil.wrapString("c"));
    MsgPackUtil.assertEquality(varC, "3");

    final DirectBuffer varD =
        variablesState.getVariableLocal(grandparent, BufferUtil.wrapString("d"));
    MsgPackUtil.assertEquality(varD, "4");
  }

  @Test
  public void shouldSetVariablesFromDocumentNotInChildScopes() {
    // given
    final long parent = 1;
    final long child = 2;
    declareScope(parent);
    declareScope(parent, child);

    variablesState.setVariableLocal(
        child, BufferUtil.wrapString("b"), MsgPackUtil.asMsgPack("'should-not-overwrite-this'"));

    final DirectBuffer document = MsgPackUtil.asMsgPack(b -> b.put("a", 1).put("b", 2));

    // when
    variablesState.setVariablesFromDocument(parent, document);

    // then
    final DirectBuffer varA = variablesState.getVariableLocal(parent, BufferUtil.wrapString("a"));
    MsgPackUtil.assertEquality(varA, "1");

    final DirectBuffer varBParent =
        variablesState.getVariableLocal(parent, BufferUtil.wrapString("b"));
    MsgPackUtil.assertEquality(varBParent, "2");

    final DirectBuffer varBChild =
        variablesState.getVariableLocal(child, BufferUtil.wrapString("b"));
    MsgPackUtil.assertEquality(varBChild, "'should-not-overwrite-this'");
  }

  @Test
  public void shouldSetVariablesFromDocumentAsObject() {
    // given
    final long scope = 1;
    declareScope(scope);

    final DirectBuffer document =
        MsgPackUtil.asMsgPack(b -> b.put("a", Collections.singletonMap("x", 1)));

    // when
    variablesState.setVariablesFromDocument(scope, document);

    // then
    final DirectBuffer varA = variablesState.getVariableLocal(scope, BufferUtil.wrapString("a"));
    MsgPackUtil.assertEquality(varA, "{'x': 1}");
  }

  @Test
  public void shouldSetVariablesFromDocumentNotInParentScope() {
    // given
    final long parent = 1;
    final long child = 2;
    declareScope(parent);
    declareScope(parent, child);

    variablesState.setVariableLocal(
        parent, BufferUtil.wrapString("a"), MsgPackUtil.asMsgPack("'should-not-overwrite-this'"));
    variablesState.setVariableLocal(
        child, BufferUtil.wrapString("a"), MsgPackUtil.asMsgPack("'should-overwrite-this'"));

    final DirectBuffer document = MsgPackUtil.asMsgPack(b -> b.put("a", 1).put("b", 2));

    // when
    variablesState.setVariablesFromDocument(child, document);

    // then
    final DirectBuffer varParent =
        variablesState.getVariableLocal(parent, BufferUtil.wrapString("a"));
    MsgPackUtil.assertEquality(varParent, "'should-not-overwrite-this'");

    final DirectBuffer newVarParent =
        variablesState.getVariableLocal(parent, BufferUtil.wrapString("b"));
    MsgPackUtil.assertEquality(newVarParent, "2");

    final DirectBuffer varChild =
        variablesState.getVariableLocal(child, BufferUtil.wrapString("a"));
    MsgPackUtil.assertEquality(varChild, "1");
  }

  /** Making sure the method is reusable and does not leave data structures dirty */
  @Test
  public void shouldSetVariablesFromDocumentRepeatedly() {
    // given
    final long scope1 = 1;
    final long scope2 = 2;
    declareScope(scope1);
    declareScope(scope2);

    variablesState.setVariablesFromDocument(scope1, MsgPackUtil.asMsgPack("{'a': 1, 'b': 2}"));

    // when
    variablesState.setVariablesFromDocument(scope2, MsgPackUtil.asMsgPack("{'x': 3}"));

    // then
    final DirectBuffer scope1Doc = variablesState.getVariablesAsDocument(scope1);
    MsgPackUtil.assertEquality(scope1Doc, "{'a': 1, 'b': 2}");

    final DirectBuffer scope2Doc = variablesState.getVariablesAsDocument(scope2);
    MsgPackUtil.assertEquality(scope2Doc, "{'x': 3}");
  }

  @Test
  public void shouldInvokeListenerOnCreate() {
    // given
    final RecordingVariableListener listener = new RecordingVariableListener();
    variablesState.setListener(listener);

    // when
    variablesState.setVariableLocal(1L, wrapString("x"), wrapString("foo"));

    // then
    assertThat(listener.created).hasSize(1);
    assertThat(listener.created.get(0).name).isEqualTo("x");
    assertThat(listener.created.get(0).value).isEqualTo("foo".getBytes());
    assertThat(listener.created.get(0).variableScopeKey).isEqualTo(1L);
    assertThat(listener.created.get(0).rootScopeKey).isEqualTo(1L);

    assertThat(listener.updated).isEmpty();
  }

  @Test
  public void shouldInvokeListenerOnUpdate() {
    // given
    final RecordingVariableListener listener = new RecordingVariableListener();
    variablesState.setListener(listener);

    // when
    variablesState.setVariableLocal(1L, wrapString("x"), wrapString("foo"));
    variablesState.setVariableLocal(1L, wrapString("x"), wrapString("bar"));

    // then
    assertThat(listener.created).hasSize(1);

    assertThat(listener.updated).hasSize(1);
    assertThat(listener.updated.get(0).name).isEqualTo("x");
    assertThat(listener.updated.get(0).value).isEqualTo("bar".getBytes());
    assertThat(listener.updated.get(0).variableScopeKey).isEqualTo(1L);
    assertThat(listener.updated.get(0).rootScopeKey).isEqualTo(1L);
  }

  @Test
  public void shouldNotInvokeListenerIfNotChanged() {
    // given
    final RecordingVariableListener listener = new RecordingVariableListener();
    variablesState.setListener(listener);

    // when
    variablesState.setVariableLocal(1L, wrapString("x"), wrapString("foo"));
    variablesState.setVariableLocal(1L, wrapString("x"), wrapString("foo"));

    // then
    assertThat(listener.created).hasSize(1);

    assertThat(listener.updated).hasSize(0);
  }

  @Test
  public void shouldInvokeListenerIfSetVariablesLocalFromDocument() {
    // given
    final RecordingVariableListener listener = new RecordingVariableListener();
    variablesState.setListener(listener);

    // when
    variablesState.setVariablesLocalFromDocument(
        1L, MsgPackUtil.asMsgPack("{'x':'foo', 'y':'bar'}"));

    // then
    assertThat(listener.created).hasSize(2);
    assertThat(listener.created.get(0).name).isEqualTo("x");
    assertThat(listener.created.get(0).value).isEqualTo(stringToMsgpack("foo"));
    assertThat(listener.created.get(0).variableScopeKey).isEqualTo(1L);
    assertThat(listener.created.get(0).rootScopeKey).isEqualTo(1L);

    assertThat(listener.created.get(1).name).isEqualTo("y");
    assertThat(listener.created.get(1).value).isEqualTo(stringToMsgpack("bar"));
    assertThat(listener.created.get(1).variableScopeKey).isEqualTo(1L);
    assertThat(listener.created.get(0).rootScopeKey).isEqualTo(1L);

    assertThat(listener.updated).isEmpty();
  }

  @Test
  public void shouldInvokeListenerIfSetVariablesFromDocument() {
    // given
    final RecordingVariableListener listener = new RecordingVariableListener();
    variablesState.setListener(listener);

    final long parentScope = 1L;
    final long childScope = 2L;

    declareScope(parentScope);
    declareScope(parentScope, childScope);

    variablesState.setVariablesLocalFromDocument(childScope, MsgPackUtil.asMsgPack("{'x':'foo'}"));

    // when
    variablesState.setVariablesFromDocument(
        childScope, MsgPackUtil.asMsgPack("{'x':'bar', 'y':'bar'}"));

    // then
    assertThat(listener.created).hasSize(2);
    assertThat(listener.created.get(1).name).isEqualTo("y");
    assertThat(listener.created.get(1).value).isEqualTo(stringToMsgpack("bar"));
    assertThat(listener.created.get(1).variableScopeKey).isEqualTo(parentScope);
    assertThat(listener.created.get(1).rootScopeKey).isEqualTo(parentScope);

    assertThat(listener.updated).hasSize(1);
    assertThat(listener.updated.get(0).name).isEqualTo("x");
    assertThat(listener.updated.get(0).value).isEqualTo(stringToMsgpack("bar"));
    assertThat(listener.updated.get(0).variableScopeKey).isEqualTo(childScope);
    assertThat(listener.updated.get(0).rootScopeKey).isEqualTo(parentScope);
  }

  @Test
  public void shouldSetPayload() {
    // when
    variablesState.setPayload(1L, wrapString("a"));
    variablesState.setPayload(2L, wrapString("b"));

    // then
    assertThat(variablesState.getPayload(1L)).isEqualTo(wrapString("a"));
    assertThat(variablesState.getPayload(2L)).isEqualTo(wrapString("b"));
  }

  @Test
  public void shouldRemovePayload() {
    // given
    variablesState.setPayload(1L, wrapString("a"));
    variablesState.setPayload(2L, wrapString("b"));

    // when
    variablesState.removePayload(1L);

    // then
    assertThat(variablesState.getPayload(1L)).isNull();
    assertThat(variablesState.getPayload(2L)).isEqualTo(wrapString("b"));
  }

  private byte[] stringToMsgpack(String value) {
    return MsgPackUtil.encodeMsgPack(b -> b.packString(value)).byteArray();
  }

  private void declareScope(long key) {
    declareScope(-1, key);
  }

  private void declareScope(long parentKey, long key) {
    final ElementInstance parent = elementInstanceState.getInstance(parentKey);

    final TypedRecord<WorkflowInstanceRecord> record = mockTypedRecord(key, parentKey);
    elementInstanceState.newInstance(
        parent, key, record.getValue(), WorkflowInstanceIntent.ELEMENT_ACTIVATING);
    elementInstanceState.flushDirtyState();
  }

  private TypedRecord<WorkflowInstanceRecord> mockTypedRecord(long key, long parentKey) {
    final WorkflowInstanceRecord workflowInstanceRecord = createWorkflowInstanceRecord(parentKey);

    final TypedRecord<WorkflowInstanceRecord> typedRecord = mock(TypedRecord.class);
    when(typedRecord.getKey()).thenReturn(key);
    when(typedRecord.getValue()).thenReturn(workflowInstanceRecord);
    final RecordMetadata metadata = new RecordMetadata();
    metadata.intent(WorkflowInstanceIntent.ELEMENT_ACTIVATED);
    when(typedRecord.getMetadata()).thenReturn(metadata);

    return typedRecord;
  }

  private WorkflowInstanceRecord createWorkflowInstanceRecord(long parentKey) {
    final WorkflowInstanceRecord workflowInstanceRecord = new WorkflowInstanceRecord();

    if (parentKey >= 0) {
      workflowInstanceRecord.setFlowScopeKey(parentKey);
    }

    return workflowInstanceRecord;
  }

  private class RecordingVariableListener implements VariableListener {

    private class VariableChange {
      private final String name;
      private final byte[] value;
      private final long variableScopeKey;
      private final long rootScopeKey;

      VariableChange(String name, byte[] value, long variableScopeKey, long rootScopeKey) {
        this.name = name;
        this.value = value;
        this.variableScopeKey = variableScopeKey;
        this.rootScopeKey = rootScopeKey;
      }
    }

    private final List<VariableChange> created = new ArrayList<>();
    private final List<VariableChange> updated = new ArrayList<>();

    @Override
    public void onCreate(
        DirectBuffer name, DirectBuffer value, long variableScopeKey, long rootScopeKey) {
      final VariableChange change =
          new VariableChange(
              bufferAsString(name),
              BufferUtil.bufferAsArray(value),
              variableScopeKey,
              rootScopeKey);
      created.add(change);
    }

    @Override
    public void onUpdate(
        DirectBuffer name, DirectBuffer value, long variableScopeKey, long rootScopeKey) {
      final VariableChange change =
          new VariableChange(
              bufferAsString(name),
              BufferUtil.bufferAsArray(value),
              variableScopeKey,
              rootScopeKey);
      updated.add(change);
    }
  }
}
