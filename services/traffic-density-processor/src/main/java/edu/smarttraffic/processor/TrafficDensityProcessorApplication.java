package edu.smarttraffic.processor;

import edu.smarttraffic.processor.adapter.in.kafka.KafkaStreamsTrafficDensityProcessor;
import edu.smarttraffic.processor.adapter.out.rabbitmq.RabbitMqOAuthTrafficUpdatePublisher;
import java.io.IOException;
import java.util.Map;

public final class TrafficDensityProcessorApplication {
    private TrafficDensityProcessorApplication() {
    }

    public static void main(String[] args) {
        Map<String, String> environment = System.getenv();
        RabbitMqOAuthTrafficUpdatePublisher publisher =
                new RabbitMqOAuthTrafficUpdatePublisher(environment);
        KafkaStreamsTrafficDensityProcessor processor =
                KafkaStreamsTrafficDensityProcessor.fromEnvironment(environment, publisher);
        Thread shutdownHook = new Thread(
                () -> close(processor, publisher),
                "traffic-density-processor-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            processor.start();
        } catch (RuntimeException | Error exception) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            try {
                close(processor, publisher);
            } catch (RuntimeException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    private static void close(
            KafkaStreamsTrafficDensityProcessor processor,
            RabbitMqOAuthTrafficUpdatePublisher publisher) {
        RuntimeException closeFailure = null;
        try {
            processor.close();
        } catch (RuntimeException exception) {
            closeFailure = exception;
        }
        try {
            publisher.close();
        } catch (IOException exception) {
            RuntimeException publisherCloseFailure =
                    new IllegalStateException("Could not close RabbitMQ publisher", exception);
            if (closeFailure == null) {
                closeFailure = publisherCloseFailure;
            } else {
                closeFailure.addSuppressed(publisherCloseFailure);
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }
}
