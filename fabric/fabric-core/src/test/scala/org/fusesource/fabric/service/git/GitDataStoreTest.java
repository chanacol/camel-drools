/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.fabric.service.git;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.fusesource.fabric.utils.Strings;
import org.fusesource.fabric.zookeeper.spring.ZKServerFactoryBean;
import org.gitective.core.RepositoryUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.setData;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GitDataStoreTest {

    private ZKServerFactoryBean sfb;
    private CuratorFramework curator;
    private Git git;
    private Git remote;
    private GitDataStore dataStore = new GitDataStore();
    private String basedir;

    @Before
    public void setUp() throws Exception {
        sfb = new ZKServerFactoryBean();
        delete(sfb.getDataDir());
        delete(sfb.getDataLogDir());
        sfb.afterPropertiesSet();

        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString("localhost:" + sfb.getClientPortAddress().getPort())
                .retryPolicy(new RetryOneTime(1000))
                .connectionTimeoutMs(360000);

        curator = builder.build();
        curator.start();
        curator.getZookeeperClient().blockUntilConnectedOrTimedOut();

        // setup a local and remote git repo
        basedir = System.getProperty("basedir", ".");
        File root =  new File(basedir + "/target/git").getCanonicalFile();
        delete(root);

        new File(root, "remote").mkdirs();
        remote = Git.init().setDirectory(new File(root, "remote")).call();
        remote.commit().setMessage("First Commit").setCommitter("fabric", "user@fabric").call();

        new File(root, "local").mkdirs();
        git = Git.init().setDirectory(new File(root, "local")).call();
        git.commit().setMessage("First Commit").setCommitter("fabric", "user@fabric").call();
        StoredConfig config = git.getRepository().getConfig();
        config.setString("remote", "origin", "url", "file://" + new File(root, "remote").getCanonicalPath());
        config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
        config.save();

        GitService gitService = new GitService() {
            public Git get() throws IOException {
                return git;
            }
        };

        dataStore.setCurator(curator);
        dataStore.setGitService(gitService);
        dataStore.init();
    }

    @After
    public void tearDown() throws Exception {
        dataStore.destroy();
        sfb.destroy();
    }

    @Test
    public void testDataStore() throws Exception {
        String defaultVersion = dataStore.getDefaultVersion();
        assertEquals("defaultVersion", "1.0", defaultVersion);

        // now lets import some data - using the old non-git file layout...
        String prefix = basedir + "/../fuse-fabric/src/main/resources/distro/fabric/import/fabric";
        String profileImport = prefix + "/configs/versions/1.0/profiles";
        String metricsImport = prefix + "/metrics";
        assertFolderExists(profileImport);
        assertFolderExists(metricsImport);

        dataStore.importFromFileSystem(profileImport, "fabric");
        dataStore.importFromFileSystem(metricsImport, "fabric");
        assertHasVersion(defaultVersion);

        String importedProfile = "example-dozer";
        String profile = importedProfile;
        assertProfileExists(defaultVersion, profile);

        String version = "1.1";
        assertCreateVersion(version);

        assertProfileConfiguration(version, importedProfile, "org.fusesource.fabric.agent", "parents", "camel");
        assertProfileTextFileConfigurationContains(version, "example-camel-fabric", "camel.xml", "http://camel.apache.org/schema/blueprint");

        // lets test the profile attributes
        Map<String, String> profileAttributes = dataStore.getProfileAttributes(version, importedProfile);
        System.out.println("Profile attributes: " + profileAttributes);
        String profileAttributeKey = "myKey";
        String expectedProfileAttributeValue = "myValue";
        dataStore.setProfileAttribute(version, importedProfile, profileAttributeKey, expectedProfileAttributeValue);
        profileAttributes = dataStore.getProfileAttributes(version, importedProfile);
        System.out.println("Profile attributes: " + profileAttributes);
        assertMapContains("Profile attribute[" + profileAttributeKey + "]", profileAttributes, profileAttributeKey, expectedProfileAttributeValue);


        // check we don't accidentally create a profile
        String profileNotCreated = "shouldNotBeCreated";
        assertEquals("Should not create profile: " + profileNotCreated, null, dataStore.getProfile(version, profileNotCreated, false));
        assertProfileNotExists(defaultVersion, profileNotCreated);
        assertFolderNotExists(getLocalGitFile("fabric/profiles/" + profileNotCreated));

        // now lets create some profiles in this new version
        String newProfile = "myNewProfile";
        dataStore.createProfile(version, newProfile);
        assertProfileExists(version, newProfile);

        // lazy create a profile
        String anotherNewProfile = "anotherNewProfile";
        dataStore.getProfile(version, anotherNewProfile, true);
        assertProfileExists(version, anotherNewProfile);

        version = "1.2";
        assertCreateVersion(version);

        // check this version has the profile too
        assertProfileExists(version, newProfile);
        assertProfileExists(version, profile);

        // now lets delete a profile
        dataStore.deleteProfile(version, newProfile);
        assertProfileNotExists(version, newProfile);

        // lets check the remote repo
        remote.checkout().setName("1.1").call();
        assertFolderExists(getRemoteGitFile("fabric/profiles/" + profile));
        assertFolderExists(getRemoteGitFile("fabric/profiles/" + newProfile));

        // we should pushed the property attributes file from the call to
        // dataStore.setProfileAttribute()
        assertFolderExists("we should have pushed this file remotely due to the call to dataStore.setProfileAttribute()",
                getRemoteGitFile("fabric/profiles/" + importedProfile + "/org.fusesource.fabric.datastore.properties"));

        remote.checkout().setName("1.2").call();
        assertFolderExists(getRemoteGitFile("fabric/profiles/" + profile));
        assertFolderNotExists(getRemoteGitFile("fabric/profiles/" + newProfile));

        remote.checkout().setName("1.0").call();
        assertFolderExists(getRemoteGitFile("fabric/profiles/" + profile));
        assertFolderNotExists(getRemoteGitFile("fabric/profiles/" + newProfile));


    }

    protected void assertProfileTextFileConfigurationContains(String version, String profile, String fileName, String expectedContents) {
        byte[] bytes = dataStore.getFileConfiguration(version, profile, fileName);
        String message = "file " + fileName + " in version " + version + " profile " + profile;
        assertNotNull("should have got data for " + message, bytes);
        assertTrue("empty file for file for " + message, bytes.length > 0);
        String text = new String(bytes);
        assertTrue("text file does not contain " + expectedContents + " was: " + text, text.contains(expectedContents));
    }

    protected void assertProfileConfiguration(String version, String profile, String pid, String key, String expectedValue) {
        String file = pid + ".properties";
        byte[] fileConfiguration = dataStore.getFileConfiguration(version, profile, file);
        assertNotNull("fileConfiguration", fileConfiguration);
        Map<String, byte[]> fileConfigurations = dataStore.getFileConfigurations(version, profile);
        assertNotNull("fileConfigurations", fileConfigurations);

        Map<String, String> configuration = dataStore.getConfiguration(version, profile, pid);
        assertNotNull("configuration", configuration);
        Map<String, Map<String, String>> configurations = dataStore.getConfigurations(version, profile);
        assertNotNull("configurations", configurations);

        System.out.println("Configurations: " + configurations);
        System.out.println(pid + " configuration: " + configuration);

        assertMapContains("configuration", configuration, key, expectedValue);
        assertFalse("configurations is empty!", configurations.isEmpty());
        assertFalse("fileConfigurations is empty!", fileConfigurations.isEmpty());

        Map<String, String> pidConfig = configurations.get(pid);
        assertNotNull("configurations should have an entry for pid " + pid, pidConfig);
        assertMapContains("configurations[" + pid + "]", pidConfig, key, expectedValue);

        byte[] pidBytes = fileConfigurations.get(file);
        assertNotNull("fileConfigurations should have an entry for file " + file, pidConfig);
        assertTrue("should have found some bytes for fileConfigurations entry for pid " + pid, pidBytes.length > 0);

        assertEquals("sizes of fileConfiguration.length and fileConfigurations[" + file + "].length", fileConfiguration.length, pidBytes.length);
    }

    protected void assertMapContains(String message, Map<String,String> map, String key,
                                   String expectedValue) {
        String value = map.get(key);
        assertEquals(message + "[" +key + "]", expectedValue, value);
    }

    protected File getLocalGitFile(String path) {
        return new File(GitHelpers.getRootGitDirectory(git), path);
    }

    protected File getRemoteGitFile(String path) {
        return new File(GitHelpers.getRootGitDirectory(remote), path);
    }

    protected void assertProfileExists(String version, String profile) {
        List<String> profiles = dataStore.getProfiles(version);
        assertTrue("Profile " + profile + " should exist but has: " + profiles + " for version " + version, profiles.contains(profile));
        assertFolderExists(getLocalGitFile("fabric/profiles/" + profile));
    }

    protected void assertProfileNotExists(String version, String profile) {
        List<String> profiles = dataStore.getProfiles(version);
        assertFalse(
                "Profile " + profile + " should not exist but has: " + profiles + " for version " + version,
                profiles.contains(profile));
        assertFolderNotExists(getLocalGitFile("fabric/profiles/" + profile));
    }

    protected void assertFolderExists(String path) {
        assertFolderExists(new File(path));
    }

    protected void assertFolderExists(File path) {
        assertFolderExists("", path);
    }

    protected void assertFolderExists(String message, File path) {
        assertTrue(messagePrefix(message) + "Cannot find folder: " + path, path.exists());
    }

    private String messagePrefix(String message) {
        return message + (Strings.isNotBlank(message) ? ". " : "");
    }

    protected void assertFolderNotExists(File path) {
        assertFolderNotExists("", path);
    }

    protected void assertFolderNotExists(String message, File path) {
        assertFalse(messagePrefix(message) + "Should not have found folder: " + path, path.exists());
    }

    protected void assertCreateVersion(String version) {
        dataStore.createVersion(version);

        assertHasVersion(version);

        // we should now have a remote branch of this name too
        Collection<String> remoteBranches = RepositoryUtils.getBranches(remote.getRepository());
        System.out.println("Remote branches: " + remoteBranches);
        String remoteBranch = "refs/heads/" + version;
        assertTrue("Should contain " + remoteBranch + " but has remote branches " + remoteBranches, remoteBranches.contains(remoteBranch));
    }

    protected void assertHasVersion(String version) {
        List<String> versions = dataStore.getVersions();
        System.out.println("Has versions: " + versions);

        assertNotNull("No version list returned!", versions);
        assertTrue("Should contain version", versions.contains(version));
        assertTrue("Should contain version", dataStore.hasVersion(version));
    }

    private void delete(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    delete(child);
                }
            }
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("Unable to delete file " + file);
        }
    }

}