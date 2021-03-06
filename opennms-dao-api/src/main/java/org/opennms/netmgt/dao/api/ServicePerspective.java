/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2020 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2020 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.dao.api;

import java.util.Objects;

import org.opennms.netmgt.model.OnmsApplication;
import org.opennms.netmgt.model.OnmsMonitoredService;
import org.opennms.netmgt.model.monitoringLocations.OnmsMonitoringLocation;

public class ServicePerspective {
    private OnmsMonitoredService service;
    private OnmsMonitoringLocation perspectiveLocation;

    public ServicePerspective(final OnmsMonitoredService service,
                              final OnmsMonitoringLocation perspectiveLocation) {
        this.service = Objects.requireNonNull(service);
        this.perspectiveLocation = Objects.requireNonNull(perspectiveLocation);
    }

    public OnmsMonitoredService getService() {
        return this.service;
    }

    public void setService(final OnmsMonitoredService service) {
        this.service = Objects.requireNonNull(service);
    }

    public OnmsMonitoringLocation getPerspectiveLocation() {
        return this.perspectiveLocation;
    }

    public void setPerspectiveLocation(final OnmsMonitoringLocation perspectiveLocation) {
        this.perspectiveLocation = Objects.requireNonNull(perspectiveLocation);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServicePerspective)) {
            return false;
        }
        final ServicePerspective that = (ServicePerspective) o;
        return Objects.equals(this.service, that.service) &&
               Objects.equals(this.perspectiveLocation, that.perspectiveLocation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.service, this.perspectiveLocation);
    }
}
