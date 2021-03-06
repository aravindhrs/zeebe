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
package io.zeebe.broker.workflow.processor.handlers;

import io.zeebe.broker.workflow.model.element.ExecutableFlowNode;
import io.zeebe.broker.workflow.processor.BpmnStepContext;
import io.zeebe.broker.workflow.state.VariablesState;
import io.zeebe.model.bpmn.instance.zeebe.ZeebeOutputBehavior;
import io.zeebe.msgpack.mapping.Mapping;
import io.zeebe.msgpack.mapping.MsgPackMergeTool;
import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord;
import org.agrona.DirectBuffer;

public class IOMappingHelper {

  public <T extends ExecutableFlowNode> void applyOutputMappings(BpmnStepContext<T> context) {
    final VariablesState variablesState = context.getElementInstanceState().getVariablesState();
    final MsgPackMergeTool mergeTool = context.getMergeTool();

    final T element = context.getElement();
    final WorkflowInstanceRecord record = context.getValue();
    final long elementInstanceKey = context.getRecord().getKey();
    final long flowScopeKey = record.getFlowScopeKey();
    final boolean hasOutputMappings = element.getOutputMappings().length > 0;

    final DirectBuffer payload = variablesState.getPayload(elementInstanceKey);
    if (payload != null) {
      if (hasOutputMappings) {
        variablesState.setVariablesLocalFromDocument(elementInstanceKey, payload);
      } else {
        variablesState.setVariablesFromDocument(elementInstanceKey, payload);
      }

      variablesState.removePayload(elementInstanceKey);
    }

    if (element.getOutputBehavior() != ZeebeOutputBehavior.none && hasOutputMappings) {
      mergeTool.reset();

      final DirectBuffer variables = variablesState.getVariablesAsDocument(elementInstanceKey);

      mergeTool.mergeDocumentStrictly(variables, element.getOutputMappings());
      final DirectBuffer mergedPayload = mergeTool.writeResultToBuffer();

      variablesState.setVariablesFromDocument(flowScopeKey, mergedPayload);
    }

    // TODO (saig0) #1852: temporary way to calculate the right payload
    if (flowScopeKey >= 0) {
      record.setPayload(variablesState.getVariablesAsDocument(flowScopeKey));
    }
  }

  public <T extends ExecutableFlowNode> void applyInputMappings(BpmnStepContext<T> context) {

    final MsgPackMergeTool mergeTool = context.getMergeTool();
    final T element = context.getElement();
    final Mapping[] mappings = element.getInputMappings();

    if (mappings.length > 0) {
      mergeTool.reset();

      final DirectBuffer scopeVariables =
          context
              .getElementInstanceState()
              .getVariablesState()
              .getVariablesAsDocument(context.getFlowScopeInstance().getKey());

      mergeTool.mergeDocumentStrictly(scopeVariables, mappings);
      final DirectBuffer mappedPayload = mergeTool.writeResultToBuffer();

      context
          .getElementInstanceState()
          .getVariablesState()
          .setVariablesLocalFromDocument(context.getRecord().getKey(), mappedPayload);

      context.getValue().setPayload(mappedPayload);
    }
  }
}
