/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole.auth.jdbc.tunnel;

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.guacamole.GuacamoleClientTooManyException;
import org.apache.guacamole.auth.jdbc.connection.ModeledConnection;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleResourceConflictException;
import org.apache.guacamole.auth.jdbc.JDBCEnvironment;
import org.apache.guacamole.auth.jdbc.connectiongroup.ModeledConnectionGroup;
import org.apache.guacamole.auth.jdbc.user.RemoteAuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * GuacamoleTunnelService implementation which restricts concurrency for each
 * connection and group according to a maximum number of connections and
 * maximum number of connections per user.
 */
@Singleton
public class RestrictedGuacamoleTunnelService
    extends AbstractGuacamoleTunnelService {

    /**
     * The environment of the Guacamole server.
     */
    @Inject
    private JDBCEnvironment environment;

    /**
     * Set of all currently-active user/connection pairs (seats).
     */
    private final ConcurrentHashMultiset<Seat> activeSeats = ConcurrentHashMultiset.<Seat>create();

    /**
     * Set of all currently-active connections.
     */
    private final ConcurrentHashMultiset<String> activeConnections = ConcurrentHashMultiset.<String>create();

    /**
     * Set of all currently-active user/connection group pairs (seats).
     */
    private final ConcurrentHashMultiset<Seat> activeGroupSeats = ConcurrentHashMultiset.<Seat>create();

    /**
     * Set of all currently-active connection groups.
     */
    private final ConcurrentHashMultiset<String> activeGroups = ConcurrentHashMultiset.<String>create();

    /**
     * The total number of active connections within this instance of
     * Guacamole.
     */
    private final AtomicInteger totalActiveConnections = new AtomicInteger(0);

    /**
     * Attempts to add a single instance of the given value to the given
     * multiset without exceeding the specified maximum number of values. If
     * the value cannot be added without exceeding the maximum, false is
     * returned.
     *
     * @param <T>
     *     The type of values contained within the multiset.
     *
     * @param multiset
     *     The multiset to attempt to add a value to.
     *
     * @param value
     *     The value to attempt to add.
     *
     * @param max
     *     The maximum number of each distinct value that the given multiset
     *     should hold, or zero if no limit applies.
     *
     * @return
     *     true if the value was successfully added without exceeding the
     *     specified maximum, false if the value could not be added.
     */
    private <T> boolean tryAdd(ConcurrentHashMultiset<T> multiset, T value, int max) {

        // Repeatedly attempt to add a new value to the given multiset until we
        // explicitly succeed or explicitly fail
        while (true) {

            // Get current number of values
            int count = multiset.count(value);

            // Bail out if the maximum has already been reached
            if (count >= max && max != 0)
                return false;

            // Attempt to add one more value
            if (multiset.setCount(value, count, count+1))
                return true;

            // Try again if unsuccessful

        }

    }

    /**
     * Attempts to increment the given AtomicInteger without exceeding the
     * specified maximum value. If the AtomicInteger cannot be incremented
     * without exceeding the maximum, false is returned.
     *
     * @param counter
     *     The AtomicInteger to attempt to increment.
     *
     * @param max
     *     The maximum value that the given AtomicInteger should contain, or
     *     zero if no limit applies.
     *
     * @return
     *     true if the AtomicInteger was successfully incremented without
     *     exceeding the specified maximum, false if the AtomicInteger could
     *     not be incremented.
     */
    private boolean tryIncrement(AtomicInteger counter, int max) {

        // Repeatedly attempt to increment the given AtomicInteger until we
        // explicitly succeed or explicitly fail
        while (true) {

            // Get current value
            int count = counter.get();

            // Bail out if the maximum has already been reached
            if (count >= max && max != 0)
                return false;

            // Attempt to increment
            if (counter.compareAndSet(count, count+1))
                return true;

            // Try again if unsuccessful

        }

    }

    @Override
    protected ModeledConnection acquire(RemoteAuthenticatedUser user,
            List<ModeledConnection> connections) throws GuacamoleException {

        // Do not acquire connection unless within overall limits
        if (!tryIncrement(totalActiveConnections, environment.getAbsoluteMaxConnections()))
            throw new GuacamoleResourceConflictException("Cannot connect. Overall maximum connections reached.");

        // Get username
        String username = user.getIdentifier();

        // Remove connections where weight < 0
        Iterator<ModeledConnection> i = connections.iterator();
        while(i.hasNext()) {
            Integer weight = i.next().getConnectionWeight();
            if (weight != null && weight.intValue() < 0)
                i.remove();
        }

        // Sort connections in ascending order of usage
        ModeledConnection[] sortedConnections = connections.toArray(new ModeledConnection[connections.size()]);
        Arrays.sort(sortedConnections, new Comparator<ModeledConnection>() {

            @Override
            public int compare(ModeledConnection a, ModeledConnection b) {
                
                int weightA, weightB;
                // Check if weight of a is null, assign 1 if it is.
                if (a.getConnectionWeight() == null)
                    weightA = 1;
                else
                    weightA = a.getConnectionWeight().intValue() + 1;

                // Check if weight of b is null, assign 1 if it is.
                if (b.getConnectionWeight() == null)
                    weightB = 1;
                else
                    weightB = b.getConnectionWeight().intValue() + 1;

                int connsA = getActiveConnections(a).size() + 1;
                int connsB = getActiveConnections(b).size() + 1;

                return (connsA * 10000 / weightA) - (connsB * 10000 / weightB);

            }

        });

        // Track whether acquire fails due to user-specific limits
        boolean userSpecificFailure = true;

        // Return the first unreserved connection
        for (ModeledConnection connection : sortedConnections) {

            // If connection weight is zero or negative, this host is disabled and should not be used.
            if (connection.getConnectionWeight() < 1)
                continue;

            // Attempt to aquire connection according to per-user limits
            Seat seat = new Seat(username, connection.getIdentifier());
            if (tryAdd(activeSeats, seat,
                    connection.getMaxConnectionsPerUser())) {

                // Attempt to aquire connection according to overall limits
                if (tryAdd(activeConnections, connection.getIdentifier(),
                        connection.getMaxConnections()))
                    return connection;

                // Acquire failed - retry with next connection
                activeSeats.remove(seat);

                // Failure to acquire is not user-specific
                userSpecificFailure = false;

            }

        }

        // Acquire failed
        totalActiveConnections.decrementAndGet();

        // Too many connections by this user
        if (userSpecificFailure)
            throw new GuacamoleClientTooManyException("Cannot connect. Connection already in use by this user.");

        // Too many connections, but not necessarily due purely to this user
        else
            throw new GuacamoleResourceConflictException("Cannot connect. This connection is in use.");

    }

    @Override
    protected void release(RemoteAuthenticatedUser user, ModeledConnection connection) {
        activeSeats.remove(new Seat(user.getIdentifier(), connection.getIdentifier()));
        activeConnections.remove(connection.getIdentifier());
        totalActiveConnections.decrementAndGet();
    }

    @Override
    protected void acquire(RemoteAuthenticatedUser user,
            ModeledConnectionGroup connectionGroup) throws GuacamoleException {

        // Get username
        String username = user.getIdentifier();

        // Attempt to aquire connection group according to per-user limits
        Seat seat = new Seat(username, connectionGroup.getIdentifier());
        if (tryAdd(activeGroupSeats, seat,
                connectionGroup.getMaxConnectionsPerUser())) {

            // Attempt to aquire connection group according to overall limits
            if (tryAdd(activeGroups, connectionGroup.getIdentifier(),
                    connectionGroup.getMaxConnections()))
                return;

            // Acquire failed
            activeGroupSeats.remove(seat);

            // Failure to acquire is not user-specific
            throw new GuacamoleResourceConflictException("Cannot connect. This connection group is in use.");

        }

        // Already in use by this user
        throw new GuacamoleClientTooManyException("Cannot connect. Connection group already in use by this user.");

    }

    @Override
    protected void release(RemoteAuthenticatedUser user,
            ModeledConnectionGroup connectionGroup) {
        activeGroupSeats.remove(new Seat(user.getIdentifier(), connectionGroup.getIdentifier()));
        activeGroups.remove(connectionGroup.getIdentifier());
    }

}
