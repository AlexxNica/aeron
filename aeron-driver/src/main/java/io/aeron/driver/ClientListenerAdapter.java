/*
 * Copyright 2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver;

import io.aeron.command.*;
import io.aeron.driver.exceptions.ControlProtocolException;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.errors.DistinctErrorLog;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.agrona.concurrent.status.AtomicCounter;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static io.aeron.CommonContext.SPY_PREFIX;
import static io.aeron.ErrorCode.GENERIC_ERROR;
import static io.aeron.command.ControlProtocolEvents.*;

/**
 * Receives commands from Aeron clients and dispatches them to the {@link DriverConductor} for processing.
 */
class ClientListenerAdapter implements MessageHandler
{
    /**
     * Limit for the number of messages to be read in each receive.
     */
    public static final int MESSAGE_COUNT_LIMIT = 10;

    private final PublicationMessageFlyweight publicationMsgFlyweight = new PublicationMessageFlyweight();
    private final SubscriptionMessageFlyweight subscriptionMsgFlyweight = new SubscriptionMessageFlyweight();
    private final CorrelatedMessageFlyweight correlatedMsgFlyweight = new CorrelatedMessageFlyweight();
    private final RemoveMessageFlyweight removeMsgFlyweight = new RemoveMessageFlyweight();
    private final DestinationMessageFlyweight destinationMsgFlyweight = new DestinationMessageFlyweight();
    private final DriverConductor conductor;
    private final RingBuffer toDriverCommands;
    private final ClientProxy clientProxy;
    private final AtomicCounter errors;
    private final DistinctErrorLog errorLog;

    ClientListenerAdapter(
        final AtomicCounter errors,
        final DistinctErrorLog errorLog,
        final RingBuffer toDriverCommands,
        final ClientProxy clientProxy,
        final DriverConductor driverConductor)
    {
        this.errors = errors;
        this.errorLog = errorLog;
        this.toDriverCommands = toDriverCommands;
        this.clientProxy = clientProxy;
        this.conductor = driverConductor;
    }

    public int receive()
    {
        return toDriverCommands.read(this, MESSAGE_COUNT_LIMIT);
    }

    @SuppressWarnings("MethodLength")
    public void onMessage(
        final int msgTypeId,
        final MutableDirectBuffer buffer,
        final int index,
        @SuppressWarnings("unused") final int length)
    {
        long correlationId = 0;

        try
        {
            switch (msgTypeId)
            {
                case ADD_PUBLICATION:
                {
                    final PublicationMessageFlyweight publicationMessageFlyweight = publicationMsgFlyweight;
                    publicationMessageFlyweight.wrap(buffer, index);

                    correlationId = publicationMessageFlyweight.correlationId();
                    final int streamId = publicationMessageFlyweight.streamId();
                    final long clientId = publicationMessageFlyweight.clientId();
                    final String channel = publicationMessageFlyweight.channel();

                    if (channel.startsWith(IPC_CHANNEL))
                    {
                        conductor.onAddIpcPublication(channel, streamId, correlationId, clientId);
                    }
                    else
                    {
                        conductor.onAddNetworkPublication(channel, streamId, correlationId, clientId);
                    }
                    break;
                }

                case REMOVE_PUBLICATION:
                {
                    final RemoveMessageFlyweight removeMessageFlyweight = removeMsgFlyweight;
                    removeMessageFlyweight.wrap(buffer, index);
                    correlationId = removeMessageFlyweight.correlationId();
                    conductor.onRemovePublication(removeMessageFlyweight.registrationId(), correlationId);
                    break;
                }

                case ADD_SUBSCRIPTION:
                {
                    final SubscriptionMessageFlyweight subscriptionMessageFlyweight = subscriptionMsgFlyweight;
                    subscriptionMessageFlyweight.wrap(buffer, index);

                    correlationId = subscriptionMessageFlyweight.correlationId();
                    final int streamId = subscriptionMessageFlyweight.streamId();
                    final long clientId = subscriptionMessageFlyweight.clientId();
                    final String channel = subscriptionMessageFlyweight.channel();

                    if (channel.startsWith(IPC_CHANNEL))
                    {
                        conductor.onAddIpcSubscription(channel, streamId, correlationId, clientId);
                    }
                    else if (channel.startsWith(SPY_PREFIX))
                    {
                        conductor.onAddSpySubscription(
                            channel.substring(SPY_PREFIX.length()), streamId, correlationId, clientId);
                    }
                    else
                    {
                        conductor.onAddNetworkSubscription(channel, streamId, correlationId, clientId);
                    }
                    break;
                }

                case REMOVE_SUBSCRIPTION:
                {
                    final RemoveMessageFlyweight removeMessageFlyweight = removeMsgFlyweight;
                    removeMessageFlyweight.wrap(buffer, index);
                    correlationId = removeMessageFlyweight.correlationId();
                    conductor.onRemoveSubscription(removeMessageFlyweight.registrationId(), correlationId);
                    break;
                }

                case ADD_DESTINATION:
                {
                    final DestinationMessageFlyweight addDestinationMsgFlyweight = destinationMsgFlyweight;
                    addDestinationMsgFlyweight.wrap(buffer, index);
                    correlationId = addDestinationMsgFlyweight.correlationId();
                    final long channelRegistrationId = addDestinationMsgFlyweight.registrationCorrelationId();
                    final String channel = addDestinationMsgFlyweight.channel();

                    conductor.onAddDestination(channelRegistrationId, channel, correlationId);
                    break;
                }

                case REMOVE_DESTINATION:
                {
                    final DestinationMessageFlyweight addDestinationMsgFlyweight = destinationMsgFlyweight;
                    addDestinationMsgFlyweight.wrap(buffer, index);
                    correlationId = addDestinationMsgFlyweight.correlationId();
                    final long channelRegistrationId = addDestinationMsgFlyweight.registrationCorrelationId();
                    final String channel = addDestinationMsgFlyweight.channel();

                    conductor.onRemoveDestination(channelRegistrationId, channel, correlationId);
                    break;
                }

                case CLIENT_KEEPALIVE:
                {
                    final CorrelatedMessageFlyweight correlatedMessageFlyweight = correlatedMsgFlyweight;
                    correlatedMessageFlyweight.wrap(buffer, index);
                    conductor.onClientKeepalive(correlatedMessageFlyweight.clientId());
                    break;
                }
            }
        }
        catch (final ControlProtocolException ex)
        {
            clientProxy.onError(ex.errorCode(), ex.getMessage(), correlationId);
            recordError(ex);
        }
        catch (final Exception ex)
        {
            clientProxy.onError(GENERIC_ERROR, ex.getMessage(), correlationId);
            recordError(ex);
        }
    }

    private void recordError(final Exception ex)
    {
        errors.increment();
        errorLog.record(ex);
    }
}