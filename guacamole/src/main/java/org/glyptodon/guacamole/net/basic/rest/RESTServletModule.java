package org.glyptodon.guacamole.net.basic.rest;

/*
 *  Guacamole - Clientless Remote Desktop
 *  Copyright (C) 2010  Michael Jumper
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.google.inject.Scopes;
import com.google.inject.servlet.ServletModule;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
import org.codehaus.jackson.jaxrs.JacksonJsonProvider;
import org.glyptodon.guacamole.net.basic.rest.auth.LoginRESTService;
import org.glyptodon.guacamole.net.basic.rest.connection.ConnectionRESTService;
import org.glyptodon.guacamole.net.basic.rest.connectiongroup.ConnectionGroupRESTService;
import org.glyptodon.guacamole.net.basic.rest.permission.PermissionRESTService;
import org.glyptodon.guacamole.net.basic.rest.user.UserRESTService;

/**
 * A Guice Module to set up the servlet mappings for the Guacamole REST API.
 * 
 * @author James Muehlner
 */
public class RESTServletModule extends ServletModule {
    
    @Override
    protected void configureServlets() {
        
        // Set up the API endpoints
        bind(ConnectionRESTService.class);
        bind(ConnectionGroupRESTService.class);
        bind(PermissionRESTService.class);
        bind(UserRESTService.class);
        bind(LoginRESTService.class);
        
        // Set up the servlet and JSON mappings
        bind(GuiceContainer.class);
        bind(JacksonJsonProvider.class).in(Scopes.SINGLETON);
        serve("/*").with(GuiceContainer.class);
    }
    
}