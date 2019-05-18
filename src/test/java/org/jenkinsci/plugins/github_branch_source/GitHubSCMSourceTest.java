/*
 * The MIT License
 *
 * Copyright (c) 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package org.jenkinsci.plugins.github_branch_source;

import com.cloudbees.jenkins.GitHubRepositoryName;
import com.cloudbees.jenkins.GitHubRepositoryNameContributor;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.SingleRootFileSource;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseTransformer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.ExtensionList;
import hudson.model.Action;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.SecurityRealm;
import hudson.util.ListBoxModel;
import hudson.util.LogTaskListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.metadata.ObjectMetadataAction;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import java.util.EnumSet;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class GitHubSCMSourceTest {
    /**
     * All tests in this class only use Jenkins for the extensions
     */
    @ClassRule
    public static JenkinsRule r = new JenkinsRule();

    public static WireMockRuleFactory factory = new WireMockRuleFactory();

    @Rule
    public WireMockRule githubRaw = factory.getRule(WireMockConfiguration.options()
            .dynamicPort()
            .usingFilesUnderClasspath("raw")
    );
    @Rule
    public WireMockRule githubApi = factory.getRule(WireMockConfiguration.options()
            .dynamicPort()
            .usingFilesUnderClasspath("api")
            .extensions(
                    new ResponseTransformer() {
                        @Override
                        public Response transform(Request request, Response response, FileSource files,
                                                  Parameters parameters) {
                            if ("application/json"
                                    .equals(response.getHeaders().getContentTypeHeader().mimeTypePart())) {
                                return Response.Builder.like(response)
                                        .but()
                                        .body(response.getBodyAsString()
                                                .replace("https://api.github.com/",
                                                        "http://localhost:" + githubApi.port() + "/")
                                                .replace("https://raw.githubusercontent.com/",
                                                        "http://localhost:" + githubRaw.port() + "/")
                                        )
                                        .build();
                            }
                            return response;
                        }

                        @Override
                        public String getName() {
                            return "url-rewrite";
                        }

                    })
    );
    private GitHubSCMSource source;
    private GitHub github;
    private GHRepository repo;


    @Before
    public void prepareMockGitHub() throws Exception {
        new File("src/test/resources/api/mappings").mkdirs();
        new File("src/test/resources/api/__files").mkdirs();
        new File("src/test/resources/raw/mappings").mkdirs();
        new File("src/test/resources/raw/__files").mkdirs();
        githubApi.enableRecordMappings(new SingleRootFileSource("src/test/resources/api/mappings"),
                new SingleRootFileSource("src/test/resources/api/__files"));
        githubRaw.enableRecordMappings(new SingleRootFileSource("src/test/resources/raw/mappings"),
                new SingleRootFileSource("src/test/resources/raw/__files"));

        githubApi.stubFor(
            get(urlEqualTo("/repos/cloudbeers/yolo/pulls/2"))
            .inScenario("Pull Request Merge Hash")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(
                aResponse()
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withBodyFile("body-yolo-pulls-2-mergeable-null.json"))
            .willSetStateTo("Pull Request Merge Hash - retry 1"));

        githubApi.stubFor(
            get(urlEqualTo("/repos/cloudbeers/yolo/pulls/2"))
            .inScenario("Pull Request Merge Hash")
            .whenScenarioStateIs("Pull Request Merge Hash - retry 1")
            .willReturn(
                aResponse()
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withBodyFile("body-yolo-pulls-2-mergeable-null.json"))
            .willSetStateTo("Pull Request Merge Hash - retry 2"));

        githubApi.stubFor(
            get(urlEqualTo("/repos/cloudbeers/yolo/pulls/2"))
            .inScenario("Pull Request Merge Hash")
            .whenScenarioStateIs("Pull Request Merge Hash - retry 2")
            .willReturn(
                aResponse()
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withBodyFile("body-yolo-pulls-2-mergeable-true.json"))
            .willSetStateTo("Pull Request Merge Hash - retry 2"));

        githubApi.stubFor(
                get(urlMatching(".*")).atPriority(10).willReturn(aResponse().proxiedFrom("https://api.github.com/")));
        githubRaw.stubFor(get(urlMatching(".*")).atPriority(10)
                .willReturn(aResponse().proxiedFrom("https://raw.githubusercontent.com/")));
        source = new GitHubSCMSource("cloudbeers", "yolo");
        source.setApiUri("http://localhost:" + githubApi.port());
        source.setTraits(Arrays.asList(new BranchDiscoveryTrait(true, true), new ForkPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.MERGE), new ForkPullRequestDiscoveryTrait.TrustContributors())));
        github = Connector.connect("http://localhost:" + githubApi.port(), null);
        repo = github.getRepository("cloudbeers/yolo");
    }

    @Test
    @Issue("JENKINS-48035")
    public void testGitHubRepositoryNameContributor() throws IOException {
        WorkflowMultiBranchProject job = r.createProject(WorkflowMultiBranchProject.class);
        job.setSourcesList(Arrays.asList(new BranchSource(source)));
        Collection<GitHubRepositoryName> names = GitHubRepositoryNameContributor.parseAssociatedNames(job);
        assertThat(names, contains(allOf(
                hasProperty("userName", equalTo("cloudbeers")),
                hasProperty("repositoryName", equalTo("yolo"))
        )));
        //And specifically...
        names = new ArrayList<>();
        ExtensionList.lookup(GitHubRepositoryNameContributor.class).get(GitHubSCMSourceRepositoryNameContributor.class).parseAssociatedNames(job, names);
        assertThat(names, contains(allOf(
                hasProperty("userName", equalTo("cloudbeers")),
                hasProperty("repositoryName", equalTo("yolo"))
        )));
    }

    @Test
    @Issue("JENKINS-48035")
    public void testGitHubRepositoryNameContributor_When_not_GitHub() throws IOException {
        WorkflowMultiBranchProject job = r.createProject(WorkflowMultiBranchProject.class);
        job.setSourcesList(Arrays.asList(new BranchSource(new GitSCMSource("file://tmp/something"))));
        Collection<GitHubRepositoryName> names = GitHubRepositoryNameContributor.parseAssociatedNames(job);
        assertThat(names, Matchers.<GitHubRepositoryName>empty());
        //And specifically...
        names = new ArrayList<>();
        ExtensionList.lookup(GitHubRepositoryNameContributor.class).get(GitHubSCMSourceRepositoryNameContributor.class).parseAssociatedNames(job, names);
        assertThat(names, Matchers.<GitHubRepositoryName>empty());
    }

    @Test
    @Issue("JENKINS-48035")
    public void testGitHubRepositoryNameContributor_When_not_MultiBranch() throws IOException {
        FreeStyleProject job = r.createProject(FreeStyleProject.class);
        Collection<GitHubRepositoryName> names = GitHubRepositoryNameContributor.parseAssociatedNames((Item) job);
        assertThat(names, Matchers.<GitHubRepositoryName>empty());
        //And specifically...
        names = new ArrayList<>();
        ExtensionList.lookup(GitHubRepositoryNameContributor.class).get(GitHubSCMSourceRepositoryNameContributor.class).parseAssociatedNames((Item) job, names);
        assertThat(names, Matchers.<GitHubRepositoryName>empty());
    }

    @Test
    public void fetchSmokes() throws Exception {
        SCMHeadObserver.Collector collector = SCMHeadObserver.collect();
        source.fetch(new SCMSourceCriteria() {
            @Override
            public boolean isHead(@NonNull Probe probe, @NonNull TaskListener listener) throws IOException {
                return probe.stat("README.md").getType() == SCMFile.Type.REGULAR_FILE;
            }
        }, collector, null, null);
        Map<String,SCMHead> byName = new HashMap<>();
        Map<String,SCMRevision> revByName = new HashMap<>();
        for (Map.Entry<SCMHead, SCMRevision> h: collector.result().entrySet())  {
            byName.put(h.getKey().getName(), h.getKey());
            revByName.put(h.getKey().getName(), h.getValue());
        }
        assertThat(byName.keySet(), containsInAnyOrder("PR-2", "PR-3", "master", "stephenc-patch-1"));

        assertThat(byName.get("PR-2"), instanceOf(PullRequestSCMHead.class));
        assertThat(((PullRequestSCMHead)byName.get("PR-2")).isMerge(), is(true));
        assertThat(revByName.get("PR-2"), is((SCMRevision) new PullRequestSCMRevision((PullRequestSCMHead)(byName.get("PR-2")),
                "8f1314fc3c8284d8c6d5886d473db98f2126071c",
                "c0e024f89969b976da165eecaa71e09dc60c3da1",
                "38814ca33833ff5583624c29f305be9133f27a40"
        )));
        ((PullRequestSCMRevision)revByName.get("PR-2")).validateMergeHash();

        assertThat(byName.get("PR-3"), instanceOf(PullRequestSCMHead.class));
        assertThat(((PullRequestSCMHead)byName.get("PR-3")).isMerge(), is(true));
        assertThat(revByName.get("PR-3"), is((SCMRevision) new PullRequestSCMRevision((PullRequestSCMHead)(byName.get("PR-3")),
                "8f1314fc3c8284d8c6d5886d473db98f2126071c",
                "c0e024f89969b976da165eecaa71e09dc60c3da1",
                PullRequestSCMRevision.NOT_MERGEABLE_HASH
        )));

        // validation should fail for this PR.
        Exception abort = null;
        try {
            ((PullRequestSCMRevision)revByName.get("PR-3")).validateMergeHash();
        } catch (Exception e) {
            abort = e;
        }
        assertThat(abort, instanceOf(AbortException.class));
        assertThat(abort.getMessage(), containsString("Not mergeable"));

        assertThat(byName.get("master"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("master"),
                hasProperty("hash", is("8f1314fc3c8284d8c6d5886d473db98f2126071c")
                ));
        assertThat(byName.get("stephenc-patch-1"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("stephenc-patch-1"),
                hasProperty("hash", is("095e69602bb95a278505e937e41d505ac3cdd263")
                ));
    }

    @Test
    public void fetchSmokes_badMergeCommit() throws Exception {
        // make it so the merge commit is not found returns 404
        // Causes PR 2 to fall back to null merge_commit_sha

        githubApi.stubFor(
            get(urlEqualTo("/repos/cloudbeers/yolo/commits/38814ca33833ff5583624c29f305be9133f27a40"))
            .inScenario("PR 2 Merge 404")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(
                aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withBodyFile("body-heads-master-notfound.json"))
            .willSetStateTo(Scenario.STARTED));

        SCMHeadObserver.Collector collector = SCMHeadObserver.collect();
        source.fetch(new SCMSourceCriteria() {
            @Override
            public boolean isHead(@NonNull Probe probe, @NonNull TaskListener listener) throws IOException {
                return probe.stat("README.md").getType() == SCMFile.Type.REGULAR_FILE;
            }
        }, collector, null, null);
        Map<String,SCMHead> byName = new HashMap<>();
        Map<String,SCMRevision> revByName = new HashMap<>();
        for (Map.Entry<SCMHead, SCMRevision> h: collector.result().entrySet())  {
            byName.put(h.getKey().getName(), h.getKey());
            revByName.put(h.getKey().getName(), h.getValue());
        }

        assertThat(byName.keySet(), containsInAnyOrder("PR-2", "PR-3", "master", "stephenc-patch-1"));

        assertThat(byName.get("PR-2"), instanceOf(PullRequestSCMHead.class));
        assertThat(((PullRequestSCMHead)byName.get("PR-2")).isMerge(), is(true));
        assertThat(revByName.get("PR-2"), is((SCMRevision) new PullRequestSCMRevision((PullRequestSCMHead)(byName.get("PR-2")),
                "8f1314fc3c8284d8c6d5886d473db98f2126071c",
                "c0e024f89969b976da165eecaa71e09dc60c3da1",
                null
        )));
        ((PullRequestSCMRevision)revByName.get("PR-2")).validateMergeHash();

        assertThat(byName.get("PR-3"), instanceOf(PullRequestSCMHead.class));
        assertThat(((PullRequestSCMHead)byName.get("PR-3")).isMerge(), is(true));
        assertThat(revByName.get("PR-3"), is((SCMRevision) new PullRequestSCMRevision((PullRequestSCMHead)(byName.get("PR-3")),
                "8f1314fc3c8284d8c6d5886d473db98f2126071c",
                "c0e024f89969b976da165eecaa71e09dc60c3da1",
                PullRequestSCMRevision.NOT_MERGEABLE_HASH
        )));

        // validation should fail for this PR.
        Exception abort = null;
        try {
            ((PullRequestSCMRevision)revByName.get("PR-3")).validateMergeHash();
        } catch (Exception e) {
            abort = e;
        }
        assertThat(abort, instanceOf(AbortException.class));
        assertThat(abort.getMessage(), containsString("Not mergeable"));

        assertThat(byName.get("master"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("master"),
                hasProperty("hash", is("8f1314fc3c8284d8c6d5886d473db98f2126071c")
                ));
        assertThat(byName.get("stephenc-patch-1"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("stephenc-patch-1"),
                hasProperty("hash", is("095e69602bb95a278505e937e41d505ac3cdd263")
                ));
    }

    @Test
    public void fetchSmokes_badUser() throws Exception {
        // make it so PR-2 returns a file not found for user
        githubApi.stubFor(
            get(urlEqualTo("/repos/cloudbeers/yolo/pulls/2"))
            .inScenario("Pull Request Merge Hash")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(
                aResponse()
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withBodyFile("body-yolo-pulls-2-bad-user.json")));
        githubApi.stubFor(
            get(urlEqualTo("/repos/cloudbeers/yolo/pulls?state=open"))
            .inScenario("Pull Request Merge Hash")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(
                aResponse()
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withBodyFile("body-yolo-pulls-bad-user.json")));


        SCMHeadObserver.Collector collector = SCMHeadObserver.collect();
        source.fetch(new SCMSourceCriteria() {
            @Override
            public boolean isHead(@NonNull Probe probe, @NonNull TaskListener listener) throws IOException {
                return probe.stat("README.md").getType() == SCMFile.Type.REGULAR_FILE;
            }
        }, collector, null, null);
        Map<String,SCMHead> byName = new HashMap<>();
        Map<String,SCMRevision> revByName = new HashMap<>();
        for (Map.Entry<SCMHead, SCMRevision> h: collector.result().entrySet())  {
            byName.put(h.getKey().getName(), h.getKey());
            revByName.put(h.getKey().getName(), h.getValue());
        }
        assertThat(byName.keySet(), containsInAnyOrder("PR-3", "master", "stephenc-patch-1"));

        // PR-2 fails to find user and throws file not found for user.
        // Caught and handled by removing PR-2 but scan continues.

        assertThat(byName.get("PR-3"), instanceOf(PullRequestSCMHead.class));
        assertThat(((PullRequestSCMHead)byName.get("PR-3")).isMerge(), is(true));
        assertThat(revByName.get("PR-3"), is((SCMRevision) new PullRequestSCMRevision((PullRequestSCMHead)(byName.get("PR-3")),
                "8f1314fc3c8284d8c6d5886d473db98f2126071c",
                "c0e024f89969b976da165eecaa71e09dc60c3da1",
                PullRequestSCMRevision.NOT_MERGEABLE_HASH
        )));

        // validation should fail for this PR.
        Exception abort = null;
        try {
            ((PullRequestSCMRevision)revByName.get("PR-3")).validateMergeHash();
        } catch (Exception e) {
            abort = e;
        }
        assertThat(abort, instanceOf(AbortException.class));
        assertThat(abort.getMessage(), containsString("Not mergeable"));

        assertThat(byName.get("master"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("master"),
                hasProperty("hash", is("8f1314fc3c8284d8c6d5886d473db98f2126071c")
                ));
        assertThat(byName.get("stephenc-patch-1"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("stephenc-patch-1"),
                hasProperty("hash", is("095e69602bb95a278505e937e41d505ac3cdd263")
                ));
    }

    @Test
    public void fetchSmokes_badTarget() throws Exception {
        // make it so the merge commit is not found returns 404
        // Causes PR 2 to fall back to null merge_commit_sha
        // Then make it so refs/heads/master returns 404 for first call
        // Causes PR 2 to fail because it cannot determine base commit.
        githubApi.stubFor(
            get(urlEqualTo("/repos/cloudbeers/yolo/commits/38814ca33833ff5583624c29f305be9133f27a40"))
            .inScenario("PR 2 Merge 404")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(
                aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withBodyFile("body-heads-master-notfound.json"))
            .willSetStateTo(Scenario.STARTED));

        githubApi.stubFor(
            get(urlEqualTo("/repos/cloudbeers/yolo/git/refs/heads/master"))
            .inScenario("PR 2 Master 404")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(
                aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withBodyFile("body-heads-master-notfound.json"))
            .willSetStateTo("Master 200"));

        githubApi.stubFor(
            get(urlEqualTo("/repos/cloudbeers/yolo/git/refs/heads/master"))
            .inScenario("PR 2 Master 404")
            .whenScenarioStateIs("Master 200")
            .willReturn(
                aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withBodyFile("body-heads-master.json"))
            .willSetStateTo("Master 200"));


        SCMHeadObserver.Collector collector = SCMHeadObserver.collect();
        source.fetch(new SCMSourceCriteria() {
            @Override
            public boolean isHead(@NonNull Probe probe, @NonNull TaskListener listener) throws IOException {
                return probe.stat("README.md").getType() == SCMFile.Type.REGULAR_FILE;
            }
        }, collector, null, null);
        Map<String,SCMHead> byName = new HashMap<>();
        Map<String,SCMRevision> revByName = new HashMap<>();
        for (Map.Entry<SCMHead, SCMRevision> h: collector.result().entrySet())  {
            byName.put(h.getKey().getName(), h.getKey());
            revByName.put(h.getKey().getName(), h.getValue());
        }
        assertThat(byName.keySet(), containsInAnyOrder("PR-3", "master", "stephenc-patch-1"));

        // PR-2 fails to find master and throws file not found for master.
        // Caught and handled by removing PR-2 but scan continues.

        assertThat(byName.get("PR-3"), instanceOf(PullRequestSCMHead.class));
        assertThat(((PullRequestSCMHead)byName.get("PR-3")).isMerge(), is(true));
        assertThat(revByName.get("PR-3"), is((SCMRevision) new PullRequestSCMRevision((PullRequestSCMHead)(byName.get("PR-3")),
                "8f1314fc3c8284d8c6d5886d473db98f2126071c",
                "c0e024f89969b976da165eecaa71e09dc60c3da1",
                PullRequestSCMRevision.NOT_MERGEABLE_HASH
        )));

        // validation should fail for this PR.
        Exception abort = null;
        try {
            ((PullRequestSCMRevision)revByName.get("PR-3")).validateMergeHash();
        } catch (Exception e) {
            abort = e;
        }
        assertThat(abort, instanceOf(AbortException.class));
        assertThat(abort.getMessage(), containsString("Not mergeable"));

        assertThat(byName.get("master"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("master"),
                hasProperty("hash", is("8f1314fc3c8284d8c6d5886d473db98f2126071c")
                ));
        assertThat(byName.get("stephenc-patch-1"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("stephenc-patch-1"),
                hasProperty("hash", is("095e69602bb95a278505e937e41d505ac3cdd263")
                ));
    }

    @Test
    public void fetchSmokesUnknownMergeable() throws Exception {
        // make it so PR-2 always returns mergeable = null
        githubApi.stubFor(
            get(urlEqualTo("/repos/cloudbeers/yolo/pulls/2"))
            .inScenario("Pull Request Merge Hash")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(
                aResponse()
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withBodyFile("body-yolo-pulls-2-mergeable-null.json")));

        SCMHeadObserver.Collector collector = SCMHeadObserver.collect();
        source.fetch(new SCMSourceCriteria() {
            @Override
            public boolean isHead(@NonNull Probe probe, @NonNull TaskListener listener) throws IOException {
                return probe.stat("README.md").getType() == SCMFile.Type.REGULAR_FILE;
            }
        }, collector, null, null);
        Map<String,SCMHead> byName = new HashMap<>();
        Map<String,SCMRevision> revByName = new HashMap<>();
        for (Map.Entry<SCMHead, SCMRevision> h: collector.result().entrySet())  {
            byName.put(h.getKey().getName(), h.getKey());
            revByName.put(h.getKey().getName(), h.getValue());
        }

        assertThat(byName.keySet(), containsInAnyOrder("PR-2", "PR-3", "master", "stephenc-patch-1"));

        assertThat(byName.get("PR-2"), instanceOf(PullRequestSCMHead.class));
        assertThat(((PullRequestSCMHead)byName.get("PR-2")).isMerge(), is(true));
        assertThat(revByName.get("PR-2"), is((SCMRevision) new PullRequestSCMRevision((PullRequestSCMHead)(byName.get("PR-2")),
                "8f1314fc3c8284d8c6d5886d473db98f2126071c",
                "c0e024f89969b976da165eecaa71e09dc60c3da1",
                null
        )));
        ((PullRequestSCMRevision)revByName.get("PR-2")).validateMergeHash();

        assertThat(byName.get("PR-3"), instanceOf(PullRequestSCMHead.class));
        assertThat(((PullRequestSCMHead)byName.get("PR-3")).isMerge(), is(true));
        assertThat(revByName.get("PR-3"), is((SCMRevision) new PullRequestSCMRevision((PullRequestSCMHead)(byName.get("PR-3")),
                "8f1314fc3c8284d8c6d5886d473db98f2126071c",
                "c0e024f89969b976da165eecaa71e09dc60c3da1",
                PullRequestSCMRevision.NOT_MERGEABLE_HASH
        )));

        // validation should fail for this PR.
        Exception abort = null;
        try {
            ((PullRequestSCMRevision)revByName.get("PR-3")).validateMergeHash();
        } catch (Exception e) {
            abort = e;
        }
        assertThat(abort, instanceOf(AbortException.class));
        assertThat(abort.getMessage(), containsString("Not mergeable"));

        assertThat(byName.get("master"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("master"),
                hasProperty("hash", is("8f1314fc3c8284d8c6d5886d473db98f2126071c")
                ));
        assertThat(byName.get("stephenc-patch-1"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("stephenc-patch-1"),
                hasProperty("hash", is("095e69602bb95a278505e937e41d505ac3cdd263")
                ));

    }

    @Test
    public void fetchAltConfig() throws Exception {
        source.setBuildForkPRMerge(false);
        source.setBuildForkPRHead(true);
        source.setBuildOriginPRMerge(false);
        source.setBuildOriginPRHead(false);
        SCMHeadObserver.Collector collector = SCMHeadObserver.collect();
        source.fetch(new SCMSourceCriteria() {
            @Override
            public boolean isHead(@NonNull Probe probe, @NonNull TaskListener listener) throws IOException {
                return probe.stat("README.md").getType() == SCMFile.Type.REGULAR_FILE;
            }
        }, collector, null, null);
        Map<String,SCMHead> byName = new HashMap<>();
        Map<String,SCMRevision> revByName = new HashMap<>();
        for (Map.Entry<SCMHead, SCMRevision> h: collector.result().entrySet())  {
            byName.put(h.getKey().getName(), h.getKey());
            revByName.put(h.getKey().getName(), h.getValue());
        }
        assertThat(byName.keySet(), containsInAnyOrder("PR-2", "PR-3", "master", "stephenc-patch-1"));
        assertThat(byName.get("PR-2"), instanceOf(PullRequestSCMHead.class));
        assertThat(((PullRequestSCMHead)byName.get("PR-2")).isMerge(), is(false));
        assertThat(revByName.get("PR-2"), is((SCMRevision) new PullRequestSCMRevision((PullRequestSCMHead)(byName.get("PR-2")),
                "8f1314fc3c8284d8c6d5886d473db98f2126071c",
                "c0e024f89969b976da165eecaa71e09dc60c3da1"
        )));
        ((PullRequestSCMRevision)revByName.get("PR-2")).validateMergeHash();

        assertThat(byName.get("PR-3"), instanceOf(PullRequestSCMHead.class));
        assertThat(((PullRequestSCMHead)byName.get("PR-3")).isMerge(), is(false));
        assertThat(revByName.get("PR-3"), is((SCMRevision) new PullRequestSCMRevision((PullRequestSCMHead)(byName.get("PR-3")),
                "8f1314fc3c8284d8c6d5886d473db98f2126071c",
                "c0e024f89969b976da165eecaa71e09dc60c3da1"
        )));

        assertThat(byName.get("master"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("master"),
                hasProperty("hash", is("8f1314fc3c8284d8c6d5886d473db98f2126071c")
                ));
        assertThat(byName.get("stephenc-patch-1"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("stephenc-patch-1"),
                hasProperty("hash", is("095e69602bb95a278505e937e41d505ac3cdd263")
                ));
    }

    @Test
    public void fetchActions() throws Exception {
        assertThat(source.fetchActions(null, null), Matchers.<Action>containsInAnyOrder(
                Matchers.<Action>is(
                        new ObjectMetadataAction(null, "You only live once", "http://yolo.example.com")
                ),
                Matchers.<Action>is(
                        new GitHubDefaultBranch("cloudbeers", "yolo", "master")
                ),
                instanceOf(GitHubRepoMetadataAction.class),
                Matchers.<Action>is(new GitHubLink("icon-github-repo", "https://github.com/cloudbeers/yolo"))));
    }

    @Test
    public void getTrustedRevisionReturnsRevisionIfRepoOwnerAndPullRequestBranchOwnerAreSameWithDifferentCase() throws Exception {
        source.setBuildOriginPRHead(true);
        PullRequestSCMRevision revision = createRevision("CloudBeers");
        assertThat(source.getTrustedRevision(revision, new LogTaskListener(Logger.getAnonymousLogger(), Level.INFO)), sameInstance((SCMRevision) revision));
    }

    private PullRequestSCMRevision createRevision(String sourceOwner) {
        PullRequestSCMHead head = new PullRequestSCMHead("", sourceOwner, "yolo", "", 0, new BranchSCMHead("non-null"),
                SCMHeadOrigin.DEFAULT, ChangeRequestCheckoutStrategy.HEAD);
        return new PullRequestSCMRevision(head, "non-null", null);
    }

    @Test
    public void doFillCredentials() throws Exception {
        final GitHubSCMSource.DescriptorImpl d =
                r.jenkins.getDescriptorByType(GitHubSCMSource.DescriptorImpl.class);
        final WorkflowMultiBranchProject dummy = r.jenkins.add(new WorkflowMultiBranchProject(r.jenkins, "dummy"), "dummy");
        SecurityRealm realm = r.jenkins.getSecurityRealm();
        AuthorizationStrategy strategy = r.jenkins.getAuthorizationStrategy();
        try {
            r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
            MockAuthorizationStrategy mockStrategy = new MockAuthorizationStrategy();
            mockStrategy.grant(Jenkins.ADMINISTER).onRoot().to("admin");
            mockStrategy.grant(Item.CONFIGURE).onItems(dummy).to("bob");
            mockStrategy.grant(Item.EXTENDED_READ).onItems(dummy).to("jim");
            r.jenkins.setAuthorizationStrategy(mockStrategy);
            ACL.impersonate(User.get("admin").impersonate(), new Runnable() {
                @Override
                public void run() {
                    ListBoxModel rsp = d.doFillCredentialsIdItems(dummy, "", "does-not-exist");
                    assertThat("Expecting only the provided value so that form config unchanged", rsp, hasSize(1));
                    assertThat("Expecting only the provided value so that form config unchanged", rsp.get(0).value,
                            Matchers.is("does-not-exist"));
                    rsp = d.doFillCredentialsIdItems(null, "", "does-not-exist");
                    assertThat("Expecting just the empty entry", rsp, hasSize(1));
                    assertThat("Expecting just the empty entry", rsp.get(0).value, Matchers.is(""));
                }
            });
            ACL.impersonate(User.get("bob").impersonate(), new Runnable() {
                @Override
                public void run() {
                    ListBoxModel rsp = d.doFillCredentialsIdItems(dummy, "", "does-not-exist");
                    assertThat("Expecting just the empty entry", rsp, hasSize(1));
                    assertThat("Expecting just the empty entry", rsp.get(0).value, Matchers.is(""));
                    rsp = d.doFillCredentialsIdItems(null, "", "does-not-exist");
                    assertThat("Expecting only the provided value so that form config unchanged", rsp, hasSize(1));
                    assertThat("Expecting only the provided value so that form config unchanged", rsp.get(0).value,
                            Matchers.is("does-not-exist"));
                }
            });
            ACL.impersonate(User.get("jim").impersonate(), new Runnable() {
                @Override
                public void run() {
                    ListBoxModel rsp = d.doFillCredentialsIdItems(dummy, "", "does-not-exist");
                    assertThat("Expecting just the empty entry", rsp, hasSize(1));
                    assertThat("Expecting just the empty entry", rsp.get(0).value, Matchers.is(""));
                }
            });
            ACL.impersonate(User.get("sue").impersonate(), new Runnable() {
                @Override
                public void run() {
                    ListBoxModel rsp = d.doFillCredentialsIdItems(dummy, "", "does-not-exist");
                    assertThat("Expecting only the provided value so that form config unchanged", rsp, hasSize(1));
                    assertThat("Expecting only the provided value so that form config unchanged", rsp.get(0).value,
                            Matchers.is("does-not-exist"));
                }
            });
        } finally {
            r.jenkins.setSecurityRealm(realm);
            r.jenkins.setAuthorizationStrategy(strategy);
            r.jenkins.remove(dummy);
        }
    }

}
