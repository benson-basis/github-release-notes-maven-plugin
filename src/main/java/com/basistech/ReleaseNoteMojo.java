/******************************************************************************
 ** This data and information is proprietary to, and a valuable trade secret
 ** of, Basis Technology Corp.  It is given in confidence by Basis Technology
 ** and may only be used as permitted under the license agreement under which
 ** it has been distributed, and in no other way.
 **
 ** Copyright (c) 2014 Basis Technology Corporation All rights reserved.
 **
 ** The technical data and information provided herein are provided with
 ** `limited rights', and the computer software provided herein is provided
 ** with `restricted rights' as those terms are defined in DAR and ASPR
 ** 7-104.9(a).
 ******************************************************************************/

package com.basistech;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.google.common.collect.Maps;
import com.google.common.io.ByteSource;
import com.google.common.io.Files;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.cxf.jaxrs.client.ClientConfiguration;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.transport.Conduit;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Write a release-note file to github.
 */
@Mojo(name = "write-release-notes", defaultPhase = LifecyclePhase.VERIFY)
public class ReleaseNoteMojo extends AbstractMojo implements Contextualizable {
    /**
     * Location of the file.
     */
    @Parameter(defaultValue = "${basedir}/RELEASE-NOTES.md", required = true)
    private File releaseNotes;

    @Parameter
    private boolean skip;

    /**
     * SCM tag version. Default is scm.tag property.
     */
    @Parameter(property = "tag")
    private String tag;

    /**
     * The user name for authentication
     */
    @Parameter(defaultValue = "${github.global.userName}")
    private String userName;

    /**
     * The password for authentication
     */
    @Parameter(defaultValue = "{github.global.password}")
    private String password;

    /**
     * The oauth2 token for authentication
     */
    @Parameter(defaultValue = "${github.global.oauth2Token}")
    private String oauth2Token;

    /**
     * The Host for API calls.
     */
    @Parameter(defaultValue = "${github.global.host}")
    private String host;

    /**
     * The <em>id</em> of the server to use to retrieve the Github credentials. This id must identify a
     * <em>server</em> from your <em>setting.xml</em> file.
     */
    @Parameter(defaultValue = "${guthub.global.server}", property = "server")
    private String serverId;

    /**
     * With GFE, you might not have enough certificates in your chain.
     */
    @Parameter
    private File keystore;

    @Component
    private MavenProject project;

    @Component
    private MavenSession session;

    @Component
    private Settings settings;

    private String owner;
    private String repoName;

    @Requirement
    private PlexusContainer container;

    /**
     * {@inheritDoc}
     */
    public void contextualize( Context context )
            throws ContextException
    {
        container = (PlexusContainer)context.get(PlexusConstants.PLEXUS_KEY);
    }

    private static class GithubUnprocessible422 {
        @JsonProperty
        String message;
        @JsonProperty
        List<Map<String, String>> errors;

        Map<String, Object> others = Maps.newHashMap();

        @JsonAnySetter
        public void setter(String key, Object value) {
            others.put(key, value);
        }
    }

    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Github Release Notes Plugin execution skipped");
            return;
        }

        String releaseNoteContent;
        try {
            releaseNoteContent = FileUtils.readFileToString(releaseNotes, "utf-8");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read notes", e);
        }

        parseScm();

        Client client = createClient();
        String tagName = getTagName();

        ReleaseInfo releaseInfo = new ReleaseInfo(tagName, null, tagName, releaseNoteContent, false, false);

        /* Start by trying to create a new one. We might have to fall back to PATCH */
        String uri;
        if ("github.com".equals(host)) {
            uri = String.format("https://api.github.com/repos/%s/%s/releases", owner, repoName);
        } else {
            uri = String.format("https://%s/api/v3/repos/%s/%s/releases", host, owner, repoName);
        }

        WebTarget target = client.target(uri);
        setupProxy(target);
        Invocation.Builder invocationBuilder = target.request(MediaType.APPLICATION_JSON_TYPE);
        Invocation invocation = invocationBuilder.buildPost(Entity.json(releaseInfo));
        try {
            Map<String, Object> response = invocation.invoke(new GenericType<Map<String, Object>>() {
            });
            getLog().info((String) response.get("url"));
        } catch (ClientErrorException cee) {
            if (cee.getResponse().getStatus() == 422) {
                GithubUnprocessible422 info = cee.getResponse().readEntity(GithubUnprocessible422.class);
                getLog().error("Error 422 " + info.message);
                for (Map<String, String> error : info.errors) {
                    getLog().error("error item:");
                    for (Map.Entry<String, String> me : error.entrySet()) {
                        getLog().error(String.format("%s: %s", me.getKey(), me.getValue()));
                    }
                }
            } else {
                Map<String, Object> info = cee.getResponse().readEntity(new GenericType<Map<String, Object>>() {
                });
                getLog().error("Error " + cee.getResponse().getStatus() + " " + info.get("message"));
            }
            throw cee;
        }
    }

    private Client createClient() throws MojoExecutionException {
        ClientBuilder builder = ClientBuilder.newBuilder();
        builder.register(new JacksonJsonProvider());
        if (keystore != null) {
            try {
                builder.trustStore(setupSslCert(keystore));
            } catch (Exception e) {
                throw new MojoExecutionException("Exception setting up SSL keystore", e);
            }
        }
        Client client = builder.build();
        if (!getAuthFromSettings(client)) {
            if (oauth2Token != null) {
                setupOauth2(client, oauth2Token);
            } else if (userName != null) {
                setupBasicAuthentication(client, userName, password);
            }
        }
        return client;
    }

    protected Server getServer(final Settings settings, final String serverId) {
        if (settings == null || serverId == null) {
            return null;
        }
        List<Server> servers = settings.getServers();
        if (servers == null || servers.isEmpty()) {
            return null;
        }

        for (Server server : servers) {
            if (serverId.equals(server.getId())) {
                return server;
            }
        }
        return null;
    }

    private void setupBasicAuthentication(Client client, final String username, final String password) {
        client.register(new ClientRequestFilter() {

            public void filter(ClientRequestContext requestContext) throws IOException {
                MultivaluedMap<String, Object> headers = requestContext.getHeaders();
                final String basicAuthentication = getBasicAuthentication();
                headers.add("Authorization", basicAuthentication);

            }

            private String getBasicAuthentication() {
                String token = username + ":" + password;
                try {
                    return "BASIC " + DatatypeConverter.printBase64Binary(token.getBytes("UTF-8"));
                } catch (UnsupportedEncodingException ex) {
                    throw new IllegalStateException("Cannot encode with UTF-8", ex);
                }
            }
        });
    }


    private boolean getAuthFromSettings(Client client) throws MojoExecutionException {
        if (serverId == null) {
            return false;
        }
        Server server = getServer(settings, serverId);
        if (server == null) {
            throw new MojoExecutionException(MessageFormat.format(
                    "Server ''{0}'' not found in settings", serverId));
        }

        getLog().debug(MessageFormat.format("Using ''{0}'' server credentials",
                serverId));

        try {
            SettingsDecrypter settingsDecrypter = container.lookup( SettingsDecrypter.class );
            SettingsDecryptionResult result =
                    settingsDecrypter.decrypt( new DefaultSettingsDecryptionRequest( server ) );
            server = result.getServer();
        }
        catch ( ComponentLookupException cle ) {
            throw new MojoExecutionException( "Unable to lookup SettingsDecrypter: " + cle.getMessage(), cle );
        }

        String serverUsername = server.getUsername();
        String serverPassword = server.getPassword();

        if (serverUsername != null && serverUsername.length() > 0
            && serverPassword != null && serverPassword.length() > 0) {
            getLog().debug("Using basic authentication with username: " + serverUsername);
            setupBasicAuthentication(client, serverUsername, serverPassword);
            return true;
        }

        // A server password without a username is assumed to be an OAuth2 token
        if (serverPassword != null && serverPassword.length() > 0) {
            getLog().debug("Using OAuth2 access token authentication");
            setupOauth2(client, serverPassword);
            return true;
        }

        getLog().debug(MessageFormat.format(
                "Server ''{0}'' is missing username/password credentials",
                serverId));
        return false;
    }

    private void setupOauth2(Client client, final String token) {
        client.register(new ClientRequestFilter() {

            public void filter(ClientRequestContext requestContext) throws IOException {
                MultivaluedMap<String, Object> headers = requestContext.getHeaders();
                headers.add("Authorization", "token " + token);
            }
        });

    }

    /**
     * Get proxy from settings
     *
     * @param settings
     * @param serverId must be non-null and non-empty
     * @return proxy or null if none matching
     */
    protected Proxy getProxy(final Settings settings, final String serverId) {
        if (settings == null)
            return null;
        List<Proxy> proxies = settings.getProxies();
        if (proxies == null || proxies.isEmpty())
            return null;

        // search id match first
        if (serverId != null && !serverId.isEmpty()) {
            for (Proxy proxy : proxies) {
                if (proxy.isActive()) {
                    final String proxyId = proxy.getId();
                    if (proxyId != null && !proxyId.isEmpty()) {
                        if (proxyId.equalsIgnoreCase(serverId)) {
                            if (("http".equalsIgnoreCase(proxy.getProtocol()) || "https".equalsIgnoreCase(proxy.getProtocol()))) {
                                if (matchNonProxy(proxy))
                                    return null;
                                else
                                    return proxy;
                            }
                        }
                    }
                }
            }
        }

        // search active proxy
        for (Proxy proxy : proxies)
            if (proxy.isActive() &&
                    ("http".equalsIgnoreCase(proxy.getProtocol()) || "https".equalsIgnoreCase(proxy.getProtocol()))
                    ) {
                if (matchNonProxy(proxy))
                    return null;
                else
                    return proxy;
            }

        return null;
    }

    /**
     * Check hostname that matched nonProxy setting
     *
     * @param proxy Maven Proxy.
     * @return matching result. true: match nonProxy
     */
    protected boolean matchNonProxy(final Proxy proxy) {
        // code from org.apache.maven.plugins.site.AbstractDeployMojo#getProxyInfo
        final String nonProxyHosts = proxy.getNonProxyHosts();
        if (null != nonProxyHosts) {
            final String[] nonProxies = nonProxyHosts.split("(,)|(;)|(\\|)");
            for (final String nonProxyHost : nonProxies) {
                //if ( StringUtils.contains( nonProxyHost, "*" ) )
                if (null != nonProxyHost && nonProxyHost.contains("*")) {
                    // Handle wildcard at the end, beginning or middle of the nonProxyHost
                    final int pos = nonProxyHost.indexOf('*');
                    String nonProxyHostPrefix = nonProxyHost.substring(0, pos);
                    String nonProxyHostSuffix = nonProxyHost.substring(pos + 1);
                    // prefix*
                    if (nonProxyHostPrefix.length() > 0 && host.startsWith(nonProxyHostPrefix)
                            && nonProxyHostSuffix.length() == 0) {
                        return true;
                    }
                    // *suffix
                    if (nonProxyHostPrefix.length() == 0 && nonProxyHostSuffix.length() > 0
                            && host.endsWith(nonProxyHostSuffix)) {
                        return true;
                    }
                    // prefix*suffix
                    if (nonProxyHostPrefix.length() > 0 && host.startsWith(nonProxyHostPrefix)
                            && nonProxyHostSuffix.length() > 0 && host.endsWith(nonProxyHostSuffix)) {
                        return true;
                    }
                } else if (host.equals(nonProxyHost)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void setupProxy(WebTarget target) throws MojoExecutionException {
        Proxy proxy = getProxy(settings, serverId);
        if (null != proxy) {
            ClientConfiguration cxfConfig = WebClient.getConfig(target);
            Conduit conduit = cxfConfig.getConduit();

            try {
                SettingsDecrypter settingsDecrypter = container.lookup(SettingsDecrypter.class);
                SettingsDecryptionResult result =
                        settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(proxy));
                proxy = result.getProxy();
            } catch (ComponentLookupException cle) {
                throw new MojoExecutionException("Unable to lookup SettingsDecrypter: " + cle.getMessage(), cle);
            }
            getLog().debug(MessageFormat.format("Found Proxy {0}:{1}",
                    proxy.getHost(), proxy.getPort()));
            HTTPConduit http = (HTTPConduit) conduit;
            http.getClient().setProxyServer(proxy.getHost());
            http.getClient().setProxyServerPort(proxy.getPort());
            http.getProxyAuthorization().setUserName(proxy.getUsername());
            http.getProxyAuthorization().setPassword(proxy.getPassword());
        }
    }

    private void parseScm() {
        final Scm scm = project.getScm();
        // the util class from egit is broken.
        // assume git: url for now.
        //scm:git:git@git.basistech.net:benson/test-release-notes.git
        String devUrl = scm.getDeveloperConnection();
        int atx = devUrl.indexOf('@');
        String some = devUrl.substring(atx + 1);
        int colidx = some.indexOf(':');
        if (host == null) {
            host = some.substring(0, colidx);
        }
        String ownerRepo = some.substring(colidx + 1);
        int slidx = ownerRepo.indexOf('/');
        owner = ownerRepo.substring(0, slidx);
        ownerRepo = ownerRepo.substring(slidx + 1);
        int dotidx = ownerRepo.indexOf('.');
        repoName = ownerRepo.substring(0, dotidx);
    }

    private String getTagName() throws MojoExecutionException {
        if (tag != null) {
            return tag;
        }

        String scmTag = (String) project.getProperties().get("scm.tag");
        if (scmTag != null) {
            return scmTag;
        }

        File releasePropsFile = new File(project.getBasedir(), "release.properties");
        if (releasePropsFile.exists()) {
            Properties releaseProps = new Properties();
            InputStream is = null;
            try {
                is = new FileInputStream(releasePropsFile);
                releaseProps.load(is);
            } catch (IOException ie) {
                throw new MojoExecutionException("Failed to read release.properties", ie);
            } finally {
                IOUtils.closeQuietly(is);
            }

            String propTag = (String) releaseProps.get("scm.tag");
            if (propTag != null) {
                return propTag;
            }
        }
        throw new MojoExecutionException("No scm tag information available.");
    }

    // this is a rather global change to the universe, is it not?
    private KeyStore setupSslCert(File trustStore) throws IOException, NoSuchAlgorithmException, KeyStoreException, CertificateException, KeyManagementException {
        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        ByteSource keystoreByteSource = Files.asByteSource(trustStore);
        InputStream keystoreStream = null;
        try {
            keystoreStream = keystoreByteSource.openStream();
            //TODO: deal with the actual password whatever it is.
            keystore.load(keystoreStream, "changeit".toCharArray());
        } finally {
            IOUtils.closeQuietly(keystoreStream);
        }
        return keystore;
    }
}
