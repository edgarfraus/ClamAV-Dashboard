package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.EndpointGroup;
import info.trizub.clamav.webclient.model.ScanJob;

import java.util.List;

public class EndpointAlertGroup {

    private final ClamdEndpoint endpoint;
    private final List<ScanJob> alerts;
    private final long openCount;

    public EndpointAlertGroup(ClamdEndpoint endpoint, List<ScanJob> alerts) {
        this.endpoint = endpoint;
        this.alerts = alerts;
        this.openCount = alerts.stream().filter(j -> !j.isAcknowledged()).count();
    }

    public ClamdEndpoint getEndpoint() { return endpoint; }

    public String getEndpointName() {
        return endpoint != null ? endpoint.getName() : "Unknown endpoint";
    }

    public String getEndpointHost() {
        return endpoint != null ? endpoint.getHost() + ":" + endpoint.getPort() : "";
    }

    public EndpointGroup getGroup() {
        return endpoint != null ? endpoint.getGroup() : null;
    }

    public String getGroupName() {
        EndpointGroup g = getGroup();
        return g != null ? g.getName() : null;
    }

    public List<ScanJob> getAlerts() { return alerts; }

    public long getOpenCount() { return openCount; }

    public long getAckedCount() { return alerts.size() - openCount; }
}
