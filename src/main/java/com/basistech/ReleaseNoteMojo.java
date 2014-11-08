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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.maven.plugins.core.GitHubProjectMojo;
import com.google.common.io.ByteSource;
import com.google.common.io.Files;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.MojoExecutionException;

import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.eclipse.egit.github.core.client.GitHubClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Properties;

/**
 * Write a release-note file to github.
 */
@Mojo( name = "write-release-notes", defaultPhase = LifecyclePhase.VERIFY )
public class ReleaseNoteMojo extends GitHubProjectMojo {
    /**
     * Location of the file.
     */
    @Parameter( defaultValue = "${basedir}/RELEASE-NOTES.md", required = true )
    private File releaseNotes;

    @Parameter
    private boolean skip;

    /**
     * SCM tag version. Default is scm.tag property.
     */
    @Parameter( property = "tag" )
    private String tag;

    /**
     * The user name for authentication
     *
     */
    @Parameter( defaultValue = "${github.global.userName}" )
    private String userName;

    /**
     * The password for authentication
     */
    @Parameter( defaultValue = "{github.global.password}")
    private String password;

    /**
     * The oauth2 token for authentication
     */
    @Parameter( defaultValue = "${github.global.oauth2Token}")
    private String oauth2Token;

    /**
     * The Host for API calls.
     */
    @Parameter( defaultValue = "${github.global.host}")
    private String host;

    /**
     * The <em>id</em> of the server to use to retrieve the Github credentials. This id must identify a
     * <em>server</em> from your <em>setting.xml</em> file.
     */
    @Parameter( defaultValue = "${guthub.global.server}")
    private String server;

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

    public void execute() throws MojoExecutionException {
        if (skip) {
            info("Github Release Notes Plugin execution skipped");
            return;
        }

        String releaseNoteContent;
        try {
            releaseNoteContent = FileUtils.readFileToString(releaseNotes, "utf-8");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read notes", e);
        }

        if (keystore != null) {
            try {
                setupSslCert(keystore);
            } catch (Exception e) {
                throw new MojoExecutionException("Failed to install SSL certificate in trust chain.", e);
            }
        }

        parseScm();

        ExtendedGithubClient client = (ExtendedGithubClient) createClient(host, userName, password, oauth2Token, server, settings, session);
        String tagName = getTagName();

        ReleaseInfo releaseInfo = new ReleaseInfo(tagName, null, tagName, releaseNoteContent, false, false);
        JsonNode response = null;
        HttpURLConnection connection = null;
        try {
        /* Start by trying to create a new one. We might have to fall back to PATCH */
            String uri = String.format("/repos/%s/%s/releases", owner, repoName);
            connection = client.createPost(uri);
            ObjectMapper mapper = new ObjectMapper();
            byte[] reqAsBytes = mapper.writeValueAsBytes(releaseInfo);
            client.sendParams(connection, reqAsBytes);
            response = mapper.readTree(client.getResponseStream(connection)); // throws for problem; if we want to fall back to PATCH, here's the place.
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to communicate with github", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        info(response.get("url").asText());
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

        String scmTag = (String)project.getProperties().get("scm.tag");
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
    private void setupSslCert(File trustStore) throws IOException, NoSuchAlgorithmException, KeyStoreException, CertificateException, KeyManagementException {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
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
        trustManagerFactory.init(keystore);
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustManagers, null);
        SSLContext.setDefault(sc);
    }

    /**
     * Create client
     * <p>
     * Subclasses can override to do any custom client configuration
     *
     * @param hostname
     * @return non-null client
     * @throws MojoExecutionException
     */
    protected GitHubClient createClient(String hostname)
            throws MojoExecutionException {
        if (!hostname.contains("://"))
            return new ExtendedGithubClient(hostname);
        try {
            URL hostUrl = new URL(hostname);
            return new ExtendedGithubClient(hostUrl.getHost(), hostUrl.getPort(),
                    hostUrl.getProtocol());
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Could not parse host URL "
                    + hostname, e);
        }
    }

    /**
     * Create client
     * <p>
     * Subclasses can override to do any custom client configuration
     *
     * @return non-null client
     */
    protected GitHubClient createClient() {
        return new ExtendedGithubClient();
    }
}
