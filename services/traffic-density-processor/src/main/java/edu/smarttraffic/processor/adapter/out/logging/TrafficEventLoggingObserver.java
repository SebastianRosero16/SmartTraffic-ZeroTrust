package edu.smarttraffic.processor.adapter.out.logging;

import edu.smarttraffic.processor.domain.event.TrafficDomainEvent;
import edu.smarttraffic.processor.domain.event.TrafficEventObserver;

public final class TrafficEventLoggingObserver implements TrafficEventObserver {
    private static final System.Logger LOGGER =
            System.getLogger(TrafficEventLoggingObserver.class.getName());

    @Override
    public void onEvent(TrafficDomainEvent event) {
        LOGGER.log(System.Logger.Level.INFO, "Observed traffic domain event: {0}", event);
    }
}
