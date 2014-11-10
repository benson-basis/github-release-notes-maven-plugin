# Github Release Notes #

This is a maven plugin that publishes release notes to github. Github has a model of releases: every git tag may, optionally, be associated with a release. See [the api documentation](https://developer.github.com/v3/repos/releases/) for more information. Releases can have Markdown release notes (plus additional artifacts). This plugin concerns itself with release notes.

This plugin facilitates the publication of release notes that are more than just a concatenation of change descriptions. As such,it is not intended as a replacement for the maven-changes-plugin. if you want to build release notes by accumulating JIRA summaries or other change-by-change records, you should use the changes plugin. If you wish that you could attach the output of the changes plugin to a github release, you could consider forking this plugin and adding to it.

The plugin anticipates the following workflow:

1. The project has a file, RELEASE-NOTES.md at the top level. At the beginning of a release cycle, it's empty.
2. Over the course of development, contributors build a document that is a coherent description of the release.
3. The release manager runs `release:prepare` to create the release tag.
4. The release manager runs this plugin to associate the release notes with the tag.
5. At the end of the release process, the release manager empties the RELEASE-NOTES.md file to prepare for the next iteration. This might involve `git mv` to archive it under the tag name, or just editing it to emptiness.

Note that the github API documentation specifies the use of the HTTP `PATCH` method to update a release. Since the 'egit' github API insists on using `HttpURLConnection`, it cannot include support for any API that uses `PATCH`, since the class refuses to allow the use of that method. Instead, this plugin uses the CXF JAX-RS 2.0 client. It does not quite manage to use the pure JAX-RS API, as that lacks support for proxies; to set up a proxy, the code reaches down to the CXF API.

