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

import com.github.maven.plugins.core.egit.GitHubClientEgit;
import org.apache.commons.io.IOUtils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * The thing from eclipse does not include PATCH.
 */
public class ExtendedGithubClient extends GitHubClientEgit {

    public ExtendedGithubClient(String hostname, int port, String scheme) {
        super(hostname, port, scheme);
    }

    public ExtendedGithubClient() {
    }

    public ExtendedGithubClient(String hostname) {
        super(hostname);
    }

    public HttpURLConnection createPatch(String uri) throws IOException {
        return this.createConnection(uri, "PATCH");
    }

    public HttpURLConnection createPost(String uri) throws IOException {
        return this.createConnection(uri, "POST");
    }

    public void sendParams(HttpURLConnection request, byte[] params) throws IOException {
        request.setDoOutput(true);
        if(params != null) {
            request.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            request.setFixedLengthStreamingMode(params.length);
            BufferedOutputStream output = new BufferedOutputStream(request.getOutputStream(), 1024);

            try {
                output.write(params);
                output.flush();
            } finally {
                IOUtils.closeQuietly(output);
            }
        } else {
            request.setFixedLengthStreamingMode(0);
            request.setRequestProperty("Content-Length", "0");
        }

    }

    public InputStream getResponseStream(HttpURLConnection request) throws IOException {
        InputStream stream = this.getStream(request);
        int code = request.getResponseCode();
        this.updateRateLimits(request);
        if(this.isOk(code)) {
            return stream;
        } else {
            throw this.createException(stream, code, request.getResponseMessage());
        }
    }
}
