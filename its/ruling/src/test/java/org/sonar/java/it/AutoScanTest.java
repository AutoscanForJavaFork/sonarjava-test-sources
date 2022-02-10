/*
 * SonarQube Java
 * Copyright (C) 2013-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.it;

import com.sonar.orchestrator.Orchestrator;
import com.sonar.orchestrator.build.MavenBuild;
import com.sonar.orchestrator.build.SonarScanner;
import com.sonar.orchestrator.locator.FileLocation;
import com.sonar.orchestrator.locator.MavenLocation;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.assertj.core.api.Assertions;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonarqube.ws.Common;
import org.sonarqube.ws.Issues.Issue;
import org.sonarqube.ws.Issues.SearchWsResponse;
import org.sonarqube.ws.client.issues.SearchRequest;

import static org.sonar.java.it.JavaRulingTest.newAdminWsClient;

public class AutoScanTest {


  @ClassRule
  public static TemporaryFolder TMP_DUMP_OLD_FOLDER = new TemporaryFolder();

  private static final int NUMBER_ISSUES_BY_PAGE = 500;

  private static final Comparator<String> RULE_KEY_COMPARATOR = (k1, k2) -> Integer.compare(
    // "S128" should be before "S1028"
    Integer.parseInt(k1.substring(1)),
    Integer.parseInt(k2.substring(1)));

  private static final Logger LOG = LoggerFactory.getLogger(AutoScanTest.class);

  @ClassRule
  public static Orchestrator orchestrator = Orchestrator.builderEnv()
    .setSonarVersion(System.getProperty("sonar.runtimeVersion", "LATEST_RELEASE[8.9]"))
    .addPlugin(FileLocation.byWildcardMavenFilename(new File("../../sonar-java-plugin/target"), "sonar-java-plugin-*.jar"))
    .addPlugin(MavenLocation.of("org.sonarsource.sonar-lits-plugin", "sonar-lits-plugin", "0.10.0.2181"))
    .build();

  @Test
  public void javaCheckTestSources() throws Exception {
    ProfileGenerator.generateSonarWay(orchestrator);

    String projectLocation = "../../java-checks-test-sources/";
    String projectKey = "java-checks-test-sources";
    String projectName = "Java Checks Test Sources";

    orchestrator.getServer().provisionProject(projectKey, projectName);
    orchestrator.getServer().associateProjectToQualityProfile(projectKey, "java", "rules");

    /**
     * 1. Run the analysis as maven project
     */
    String correctConfigIssues = FileLocation.of("target/actual/" + projectKey + "-mvn").getFile().getAbsolutePath();

    MavenBuild mavenBuild = MavenBuild.create()
      .setPom(FileLocation.of(projectLocation + "pom.xml").getFile().getCanonicalFile())
      .setCleanPackageSonarGoals()
      .addArgument("-DskipTests")
      .addArgument("-Panalyze-tests")
      .setProperty("sonar.projectKey", projectKey)
      .setProperty("sonar.projectName", projectName)
      .setProperty("sonar.cpd.exclusions", "**/*")
      .setProperty("sonar.skipPackageDesign", "true")
      .setProperty("sonar.lits.dump.old", TMP_DUMP_OLD_FOLDER.newFolder().getAbsolutePath())
      .setProperty("sonar.lits.dump.new", correctConfigIssues)
      .setProperty("sonar.lits.differences", FileLocation.of("target/" + projectKey + "-mvn_differences").getFile().getAbsolutePath())
      .setProperty("sonar.internal.analysis.failFast", "true");

    orchestrator.executeBuild(mavenBuild);

    /**
     * 2. Execute the analysis as sonar-scanner project, without any bytecode nor dependencies/libraries
     */
    SonarScanner sonarScannerBuild = SonarScanner.create(FileLocation.of(projectLocation).getFile())
      .setProjectKey(projectKey)
      .setProjectName(projectName)
      .setProjectVersion("0.1.0-SNAPSHOT")
      .setSourceEncoding("UTF-8")
      .setSourceDirs("src/main/java/")
      .setTestDirs("src/test/java/")
      .setProperty("sonar.cpd.exclusions", "**/*")
      .setProperty("sonar.skipPackageDesign", "true")
      .setProperty("sonar.java.source", "17")
      // Force AutoScan mode
      .setProperty("sonar.java.internal.batchMode", "true")
      // Dummy sonar.java.binaries to pass validation
      .setProperty("sonar.java.binaries", TMP_DUMP_OLD_FOLDER.newFolder().getAbsolutePath())
      // use as "old" issues the ones from Maven analysis
      .setProperty("sonar.lits.dump.old", correctConfigIssues)
      .setProperty("sonar.lits.dump.new", FileLocation.of("target/actual/" + projectKey + "-no-binaries").getFile().getAbsolutePath())
      .setProperty("sonar.lits.differences", FileLocation.of("target/" + projectKey + "-no-binaries_differences").getFile().getAbsolutePath())
      .setProperty("sonar.internal.analysis.failFast", "true");

    orchestrator.executeBuild(sonarScannerBuild);

    /**
     * 3. Check total number of differences
     *
     * No differences would mean that we find the same issues with and without the bytecode and libraries
     */
    String differences = new String(Files.readAllBytes(FileLocation.of("target/" + projectKey + "-no-binaries_differences").getFile().toPath()), StandardCharsets.UTF_8);
    Assertions.assertThat(differences).isEqualTo("Issues differences: 2929");

    /**
     * 4. Check if differences in expectations in terms of Missing/New
     *
     * New issues: a potential FP - without complete semantic we should not raise any new issue
     * Missing issues: we don't detect them without the help of the bytecode
     */
    List<Issue> issues = issuesForProject(projectKey);
    Map<String, IssueDiff> newDifferencesByRules = calculateDifferences(issues);

    String diffByRules = new String(Files.readAllBytes(FileLocation.of("src/test/resources/autoscan/diff-by-rules.txt").getFile().toPath()), StandardCharsets.UTF_8);
    Assertions.assertThat(diffByRules).isEqualTo(prettyPrint(newDifferencesByRules));

    // LOG.info("WAITING 5MIN BEFORE CLOSING (SERVER: " + orchestrator.getServer().getUrl() + ")");
    // Thread.sleep(1000 * 60 * 5);
  }

  private static String prettyPrint(Map<String, IssueDiff> differencesByRule) {
    StringBuilder sb = new StringBuilder()
      .append("Rule;Missing;New\n")
      .append(String.format("-----;-----;-----\n"));
    int numberMissing = 0;
    int numberNew = 0;
    for (IssueDiff diff : differencesByRule.values()) {
      sb.append(String.format("%s;%d;%d\n", diff.ruleKey, diff.missingIssues, diff.newIssues));
      numberMissing += diff.missingIssues;
      numberNew += diff.newIssues;
    }
    sb.append(String.format("-----;-----;-----\n"));
    sb.append("Rule;Missing;New\n");
    sb.append(String.format("%d;%d;%d\n", differencesByRule.size(), numberMissing, numberNew));
    return sb.toString();
  }

  private static List<Issue> issuesForProject(String projectKey) {
    List<Issue> issues = new ArrayList<>();
    // pages are 1-based
    int currentPage = 1;
    long totalNumberIssues;
    long collectedIssuesNumber;

    do {
      SearchRequest searchRequest = new SearchRequest()
        .setProjects(Collections.singletonList(projectKey))
        .setStatuses(Collections.singletonList("OPEN"))
        .setP(Integer.toString(currentPage))
        .setPs(Integer.toString(NUMBER_ISSUES_BY_PAGE));

      SearchWsResponse searchResponse = newAdminWsClient(orchestrator).issues().search(searchRequest);

      issues.addAll(searchResponse.getIssuesList());

      // update number of issues
      collectedIssuesNumber = issues.size();
      totalNumberIssues = searchResponse.getTotal();
      LOG.info("Collected issues: {} / {}", collectedIssuesNumber, totalNumberIssues);
      // prepare for next page
      currentPage++;
    } while (collectedIssuesNumber != totalNumberIssues);

    return issues;
  }

  private static Map<String, IssueDiff> calculateDifferences(List<Issue> issues) {
    Map<String, IssueDiff> differences = new TreeMap<>(RULE_KEY_COMPARATOR);
    for (Issue issue : issues) {
      differences
        .computeIfAbsent(issue.getRule().substring("java:".length()), ruleKey -> new IssueDiff(ruleKey))
        .update(issue.getSeverity());
    }
    return differences;
  }

  private static class IssueDiff {
    private final String ruleKey;
    private int missingIssues;
    private int newIssues;

    private IssueDiff(String ruleKey) {
      this.ruleKey = ruleKey;
    }

    /**
     * LITS plugin marks missing issues as CRITICAL and new issues as INFO
     */
    void update(Common.Severity severity) {
      if (Common.Severity.BLOCKER == severity) {
        missingIssues += 1;
      } else {
        newIssues += 1;
      }
    }

    @Override
    public String toString() {
      return String.format("[%s;missing=%d;new=%d]", ruleKey, missingIssues, newIssues);
    }
  }
}
