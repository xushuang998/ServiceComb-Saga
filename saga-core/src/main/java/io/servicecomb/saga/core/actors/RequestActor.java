/*
 * Copyright 2017 Huawei Technologies Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.saga.core.actors;

import static io.servicecomb.saga.core.SagaResponse.NONE_RESPONSE;
import static io.servicecomb.saga.core.actors.RequestActor.Messages.MESSAGE_ABORT;
import static io.servicecomb.saga.core.actors.RequestActor.Messages.MESSAGE_COMPENSATE;
import static java.util.Arrays.asList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import io.servicecomb.saga.core.CompositeSagaResponse;
import io.servicecomb.saga.core.SagaRequest;
import io.servicecomb.saga.core.SagaResponse;
import io.servicecomb.saga.core.SagaTask;
import io.servicecomb.saga.core.TransactionFailedException;
import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.Props;

public class RequestActor extends AbstractLoggingActor {
  private final RequestActorContext context;
  private final SagaTask task;
  private final SagaRequest request;

  private final List<SagaResponse> parentResponses;
  private final List<ActorRef> compensatedChildren;
  private final Set<String> pendingParents;

  private final Receive transacted;
  private final Receive aborted;

  static Props props(
      RequestActorContext context,
      SagaTask task,
      SagaRequest request) {
    return Props.create(RequestActor.class, () -> new RequestActor(context, task, request));
  }

  private RequestActor(
      RequestActorContext context,
      SagaTask task,
      SagaRequest request) {
    this.context = context;
    this.task = task;
    this.request = request;
    this.parentResponses = new ArrayList<>(request.parents().length);
    this.compensatedChildren = new LinkedList<>();
    this.pendingParents = new HashSet<>(asList(request.parents()));

    this.transacted = onReceive(task::compensate);
    this.aborted = onReceive(ignored -> {
    });
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .match(ResponseContext.class, this::handleContext)
        .match(Messages.class, MESSAGE_ABORT::equals, message -> getContext().become(aborted))
        .build();
  }

  private void handleContext(ResponseContext parentContext) {
    if (pendingParents.remove(parentContext.request().id())) {
      parentResponses.add(parentContext.response());
    }

    if (pendingParents.isEmpty()) {
      transact();
    }
  }

  private void transact() {
    try {
      boolean isChosenChild = parentResponses.stream()
          .map(context::chosenChildren)
          .anyMatch(chosenChildren -> chosenChildren.isEmpty() || chosenChildren.contains(request.id()));

      if (isChosenChild) {
        SagaResponse sagaResponse = task.commit(request, responseOf(parentResponses));
        context.childrenOf(request).forEach(actor -> actor.tell(new ResponseContext(request, sagaResponse), self()));
        getContext().become(transacted);
      } else {
        context.childrenOf(request).forEach(actor -> actor.tell(new ResponseContext(request, NONE_RESPONSE), self()));
        getContext().become(aborted);
      }
    } catch (TransactionFailedException e) {
      log().error("Failed to run operation {}", request.transaction(), e);
      context.forAll(actor -> actor.tell(MESSAGE_ABORT, self()));
    }
  }

  private SagaResponse responseOf(List<SagaResponse> responseContexts) {
    return responseContexts.size() > 1 ? new CompositeSagaResponse(responseContexts) : responseContexts.get(0);
  }

  private Receive onReceive(Consumer<SagaRequest> requestConsumer) {
    return receiveBuilder()
        .match(Messages.class, MESSAGE_COMPENSATE::equals, message -> onCompensate(requestConsumer))
        .build();
  }

  private void onCompensate(Consumer<SagaRequest> requestConsumer) {
    compensatedChildren.add(sender());

    if (compensatedChildren.size() == context.childrenOf(request).size()) {
      requestConsumer.accept(request);
      context.parentsOf(request).forEach(actor -> actor.tell(MESSAGE_COMPENSATE, self()));
    }
  }

  enum Messages {
    MESSAGE_COMPENSATE,
    MESSAGE_ABORT
  }
}
