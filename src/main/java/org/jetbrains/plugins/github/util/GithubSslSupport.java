/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package org.jetbrains.plugins.github.util;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.lang.function.ThrowableFunction;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.contrib.ssl.EasySSLProtocolSocketFactory;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;

import java.io.IOException;

/**
 * Provides various methods to work with SSL certificate protected HTTPS connections.
 *
 * @author Kirill Likhodedov
 */
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
@Singleton
public class GithubSslSupport {
    public static GithubSslSupport getInstance() {
        return Application.get().getInstance(GithubSslSupport.class);
    }

    /**
     * Tries to execute the {@link HttpMethod} and captures the {@link sun.security.validator.ValidatorException exception} which is thrown if
     * user connects
     * to an HTTPS server with a non-trusted (probably, self-signed) SSL certificate. In which case proposes to cancel
     * the connection
     * or to proceed without certificate check.
     *
     * @param methodCreator a function to create the HttpMethod. This is required instead of just {@link HttpMethod}
     *                      instance, because the
     *                      implementation requires the HttpMethod to be recreated in certain circumstances.
     * @return the HttpMethod instance which was actually executed
     * and which can be {@link HttpMethod#getResponseBodyAsString() asked for the response}.
     * @throws IOException in case of other errors or if user declines the proposal of non-trusted connection.
     */
    @Nonnull
    public HttpMethod executeSelfSignedCertificateAwareRequest(
        @Nonnull HttpClient client,
        @Nonnull String uri,
        @Nonnull ThrowableFunction<String, HttpMethod, IOException> methodCreator
    ) throws IOException {
        HttpMethod method = methodCreator.apply(uri);
        try {
            client.executeMethod(method);
            return method;
        }
        catch (IOException e) {
            HttpMethod m = handleCertificateExceptionAndRetry(e, method.getURI().getHost(), client, method.getURI(), methodCreator);
            if (m == null) {
                throw e;
            }
            return m;
        }
    }

    @Nullable
    private static HttpMethod handleCertificateExceptionAndRetry(
        @Nonnull IOException e,
        @Nonnull String host,
        @Nonnull HttpClient client,
        @Nonnull URI uri,
        @Nonnull ThrowableFunction<String, HttpMethod, IOException> methodCreator
    ) throws IOException {
        if (!isCertificateException(e)) {
            throw e;
        }

        if (isTrusted(host)) {
            // creating a special configuration that allows connections to non-trusted HTTPS hosts
            // see the javadoc to EasySSLProtocolSocketFactory for details
            Protocol easyHttps = new Protocol("https", (ProtocolSocketFactory)new EasySSLProtocolSocketFactory(), 443);
            HostConfiguration hc = new HostConfiguration();
            hc.setHost(host, 443, easyHttps);
            String relativeUri = new URI(uri.getPathQuery(), false).getURI();
            // it is important to use relative URI here, otherwise our custom protocol won't work.
            // we have to recreate the method, because HttpMethod#setUri won't overwrite the host,
            // and changing host by hands (HttpMethodBase#setHostConfiguration) is deprecated.
            HttpMethod method = methodCreator.apply(relativeUri);
            client.executeMethod(hc, method);
            return method;
        }
        throw e;
    }

    public static boolean isCertificateException(IOException e) {
        Throwable cause = e.getCause();
        return cause != null && "sun.security.validator.ValidatorException".equals(cause.getClass().getName());
    }

    private static boolean isTrusted(@Nonnull String host) {
        return GithubSettings.getInstance().getTrustedHosts().contains(host);
    }

    private static void saveToTrusted(@Nonnull String host) {
        GithubSettings.getInstance().addTrustedHost(host);
    }

    @RequiredUIAccess
    public boolean askIfShouldProceed(final String host) {
        final String BACK_TO_SAFETY = "No, I don't trust";
        final String TRUST = "Proceed anyway";
        //int choice = Messages.showDialog("The security certificate of " + host + " is not trusted. Do you want to
        // proceed anyway?",
        //                               "Not Trusted Certificate", new String[] { BACK_TO_SAFETY, TRUST }, 0,
        // Messages.getErrorIcon());
        int choice = Messages.showIdeaMessageDialog(
            null,
            "The security certificate of " + host + " is not trusted. Do you want to proceed anyway?",
            "Not Trusted Certificate",
            new String[]{
                BACK_TO_SAFETY,
                TRUST
            },
            0,
            UIUtil.getErrorIcon(),
            null
        );
        boolean trust = (choice == 1);
        if (trust) {
            saveToTrusted(host);
        }
        return trust;
    }
}
