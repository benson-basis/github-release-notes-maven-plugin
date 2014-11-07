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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**

 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
class ReleaseInfo {
    @JsonProperty("tag_name")
    final String tagName;
    @JsonProperty("target_commitish")
    final String targetCommitish;
    @JsonProperty("name")
    final String name;
    @JsonProperty("body")
    final String body;
    @JsonProperty("draft")
    final boolean draft;
    @JsonProperty("prerelease")
    final boolean prerelease;

    ReleaseInfo(String tagName, String targetCommitish, String name, String body, boolean draft, boolean prerelease) {
        this.tagName = tagName;
        this.targetCommitish = targetCommitish;
        this.name = name;
        this.body = body;
        this.draft = draft;
        this.prerelease = prerelease;
    }
}
