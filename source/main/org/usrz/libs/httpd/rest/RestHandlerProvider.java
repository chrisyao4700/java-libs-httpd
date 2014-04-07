/* ========================================================================== *
 * Copyright 2014 USRZ.com and Pier Paolo Fumagalli                           *
 * -------------------------------------------------------------------------- *
 * Licensed under the Apache License, Version 2.0 (the "License");            *
 * you may not use this file except in compliance with the License.           *
 * You may obtain a copy of the License at                                    *
 *                                                                            *
 *  http://www.apache.org/licenses/LICENSE-2.0                                *
 *                                                                            *
 * Unless required by applicable law or agreed to in writing, software        *
 * distributed under the License is distributed on an "AS IS" BASIS,          *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   *
 * See the License for the specific language governing permissions and        *
 * limitations under the License.                                             *
 * ========================================================================== */
package org.usrz.libs.httpd.rest;

import static org.usrz.libs.utils.Check.notNull;
import static org.usrz.libs.utils.Injections.getInstance;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.core.Application;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.RequestExecutorProvider;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpContainer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpContainerProvider;
import org.glassfish.jersey.internal.inject.Injections;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerException;
import org.glassfish.jersey.server.ResourceConfig;
import org.jvnet.hk2.guice.bridge.api.GuiceBridge;
import org.jvnet.hk2.guice.bridge.api.GuiceIntoHK2Bridge;
import org.usrz.libs.httpd.inject.HttpHandlerPath;
import org.usrz.libs.logging.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.jaxrs.cfg.Annotations;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.google.inject.Injector;
import com.google.inject.Provider;


/**
 * A {@link Provider} working in conjunction with
 * <a href="https://jersey.java.net/">Jersey</a> building <em>JAX-RS</em>
 * {@link Application}s and wrapping them in an appropriate
 * <a href="https://grizzly.java.net/">Grizzly</a> {@link HttpHandler}s.
 *
 * @author <a href="mailto:pier@usrz.com">Pier Fumagalli</a>
 */
@Singleton
public class RestHandlerProvider implements Provider<HttpHandler> {

    /* Our Log instance */
    private static final Log log = new Log();

    /** Jersey's {@link ResourceConfig} for non-trivial customization */
    protected final ResourceConfig config;

    private final HttpHandlerPath path;
    private HttpHandler handler = null;
    private HttpServer server = null;
    private Injector injector = null;

    /**
     * Create a new {@link RestHandlerProvider} instance specifying the
     * underlying application <em>name</em>
     */
    public RestHandlerProvider(Consumer<ResourceConfig> consumer, HttpHandlerPath path) {
        config = new ResourceConfig();
        notNull(consumer).accept(config);
        this.path = notNull(path, "Null path"); //.value();
    }

    /* ====================================================================== */

    @Inject
    private void setInjector(Injector injector) {
        this.injector = injector;

        /* Setup our object mapper */
        final ObjectMapper mapper = getInstance(injector, ObjectMapper.class, path);

        final Map<Class<?>, Integer> contractPriorities = new HashMap<>();
        contractPriorities.put(MessageBodyWriter.class, Integer.MIN_VALUE);
        contractPriorities.put(MessageBodyReader.class, Integer.MIN_VALUE);
        config.register(new JacksonJsonProvider(mapper,
                    new Annotations[] { Annotations.JACKSON, Annotations.JAXB }),
                    Collections.unmodifiableMap(contractPriorities));

        /* Setup our JSONP body writer */
        config.register(JSONPBodyWriter.class);
    }

    @Inject
    private void setHttpServer(HttpServer server) {
        this.server = server;
    }

    /* ====================================================================== */

    /**
     * Create an {@link HttpHandler} instance ready to be used by
     * <a href="https://grizzly.java.net/">Grizzly</a>.
     */
    @Override
    public final HttpHandler get() {
        notNull(injector, "Injector not available");

        if (handler != null) return handler;

        /* Check that an injector was properly provided to us */
        if (injector == null) throw new IllegalStateException("No injector");

        /* Create a new ServiceLocator parent linked to Guice */
        final ServiceLocator locator = Injections.createLocator();
        GuiceBridge.getGuiceBridge().initializeGuiceBridge(locator);
        locator.getService(GuiceIntoHK2Bridge.class).bridgeGuiceInjector(injector);

        /* Create our handler and add it to our server configuration  */
        handler = new Handler(new ApplicationHandler(config, null, locator));
        server.getServerConfiguration().addHttpHandler(handler, path.value());
        log.info("Serving requests at \"%s\" using Jersey application");
        return handler;
    }

    /* ====================================================================== */
    /* OUR HANDLER WRAPPING JERSEY'S OWN IMPLEMENTATION                       */
    /* ====================================================================== */

    private static class Handler extends HttpHandler {

        private final GrizzlyHttpContainer handler;

        protected Handler(ApplicationHandler handler) {
            this.handler = new GrizzlyHttpContainerProvider()
                    .createContainer(GrizzlyHttpContainer.class,
                            notNull(handler, "Null handler"));
        }

        @Override
        public String getName() {
            return handler.getConfiguration().getApplicationName();
        }

        @Override
        public void service(Request request, Response response)
        throws Exception {
            try {
                handler.service(request, response);
            } catch (ContainerException exception) {
                final Throwable throwable = exception.getCause();
                log(request, throwable == null ? exception : throwable);
                throw exception;
            } catch (Exception exception) {
                log(request, exception);
                throw exception;
            }
        }

        private final void log(Request request, Throwable exception) {
            log.error(exception,
                      "Application \"%s\" unable to process request %s",
                      handler.getConfiguration().getApplicationName(),
                      request.getRequestURI());
        }

        /* ================================================================== */
        /* SIMPLY DELEGATE THE REST WITHOUT QUESTIONING                       */
        /* ================================================================== */

        @Override
        public void start() {
            handler.start();
        }

        @Override
        public void destroy() {
            handler.destroy();
        }

        @Override
        public boolean isAllowCustomStatusMessage() {
            return handler.isAllowCustomStatusMessage();
        }

        @Override
        public void setAllowCustomStatusMessage(boolean allowCustomStatusMessage) {
            handler.setAllowCustomStatusMessage(allowCustomStatusMessage);
        }

        @Override
        public boolean isAllowEncodedSlash() {
            return handler.isAllowEncodedSlash();
        }

        @Override
        public void setAllowEncodedSlash(boolean allowEncodedSlash) {
            handler.setAllowEncodedSlash(allowEncodedSlash);
        }

        @Override
        public Charset getRequestURIEncoding() {
            return handler.getRequestURIEncoding();
        }

        @Override
        public void setRequestURIEncoding(Charset requestURIEncoding) {
            handler.setRequestURIEncoding(requestURIEncoding);
        }

        @Override
        public void setRequestURIEncoding(String requestURIEncoding) {
            handler.setRequestURIEncoding(requestURIEncoding);
        }

        @Override
        public RequestExecutorProvider getRequestExecutorProvider() {
            return handler.getRequestExecutorProvider();
        }
    }

}
