package org.snomed.snowstorm.dailybuild;


import io.kaicode.elasticvc.api.BranchService;
import io.kaicode.elasticvc.domain.Branch;
import io.kaicode.elasticvc.domain.DomainEntity;
import org.ihtsdo.otf.resourcemanager.ResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceManager;
import org.ihtsdo.otf.snomedboot.ReleaseImportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.core.data.domain.CodeSystem;
import org.snomed.snowstorm.core.data.domain.CodeSystemVersion;
import org.snomed.snowstorm.core.data.services.CodeSystemService;
import org.snomed.snowstorm.core.data.services.DomainEntityConfiguration;
import org.snomed.snowstorm.core.data.services.SBranchService;
import org.snomed.snowstorm.core.rf2.RF2Type;
import org.snomed.snowstorm.core.rf2.rf2import.ImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DailyBuildService {

	private static final String DAILY_BUILD_DATE_FORMAT = "yyyy-MM-dd-HHmmss";
	private static final String LOCK_MESSAGE = "Branch locked for daily build import.";

	@Autowired
	private DailyBuildResourceConfig dailyBuildResourceConfig;

	@Autowired
	private BranchService branchService;

	@Autowired
	private SBranchService sBranchService;

	@Autowired
	private ImportService importService;

	@Autowired
	private ResourceLoader resourceLoader;

	@Autowired
	private CodeSystemService codeSystemService;

	@Autowired
	private ResourcePatternResolver resourcePatternResolver;

	@Autowired
	private DomainEntityConfiguration domainEntityConfiguration;

	private ResourceManager resourceManager;

	private Logger logger = LoggerFactory.getLogger(getClass());

	@PostConstruct
	public void init() {
		resourceManager = new ResourceManager(dailyBuildResourceConfig, resourceLoader);
	}

	void performScheduledImport(CodeSystem codeSystem) throws IOException, ReleaseImportException {
		String branchPath = codeSystem.getBranchPath();
		Branch codeSystemBranch = branchService.findBranchOrThrow(branchPath);
		if (codeSystemBranch.isLocked()) {
			logger.info("Scheduled daily build import is skipped as branch {} is already locked.", branchPath);
			return;
		}
		// check any new daily builds
		String dailyBuildSteam = getNewDailyBuildIfExists(codeSystem, codeSystemBranch.getHeadTimestamp());
		// perform rollback and import
		dailyBuildDeltaImport(codeSystem, dailyBuildSteam);
	}

	void dailyBuildDeltaImport(CodeSystem codeSystem, String dailyBuildFilename) throws IOException, ReleaseImportException {
		if (dailyBuildFilename == null) {
			return;
		}
		logger.info("New daily build {} found for {} ", dailyBuildFilename, codeSystem.getShortName());
		// Lock branch immediately to stop other instances performing daily build.
		branchService.lockBranch(codeSystem.getBranchPath(), LOCK_MESSAGE);

		rollbackDailyBuildContent(codeSystem);

		logger.info("start daily build delta import for code system " +  codeSystem.getShortName());
		String importId = importService.createJob(RF2Type.DELTA, codeSystem.getBranchPath(), false, true);
		InputStream dailyBuildStream = resourceManager.readResourceStreamOrNullIfNotExists(codeSystem.getShortName() + "/" + dailyBuildFilename);
		// Unlock branch so that delta import can be executed
		branchService.unlock(codeSystem.getBranchPath());
		importService.importArchive(importId, dailyBuildStream);
		logger.info("Daily build delta import completed for code system " +  codeSystem.getShortName());
	}

	public void rollbackDailyBuildContent(CodeSystem codeSystem) {
		// Roll back commits on Code System branch if commit starts after latest release commit
		// AND new base timestamp does not match one of the parent codesystem release branch timepoints.

		CodeSystemVersion latestVersion = codeSystemService.findLatestImportedVersion(codeSystem.getShortName());
		Date releaseCommitHead = null;
		if (latestVersion != null) {
			// Release branch base timestamp is the same as the head timestamp on the MAIN branch for the commit containing the release.
			releaseCommitHead = branchService.findLatest(latestVersion.getBranchPath()).getBase();
		}

		List<Branch> commitsToRollback = new ArrayList<>();
		String branchPath = codeSystem.getBranchPath();

		// Find commit of latest release and any after that
		Date baseForReleaseCommit = null;
		if (releaseCommitHead != null) {
			List<Branch> commits = sBranchService.findAllVersionsAfterOrEqualToTimestamp(branchPath, releaseCommitHead, Pageable.unpaged()).getContent();
			for (Branch commit : commits) {
				// Don't rollback the release commit
				if (commit.getHead().equals(releaseCommitHead)) {
					baseForReleaseCommit = commit.getBase();
					commitsToRollback.clear();
					continue;
				}
				// Don't rollback rebase commits - these are most likely code system upgrades
				if (baseForReleaseCommit != null && !commit.getBase().equals(baseForReleaseCommit)) {
					commitsToRollback.clear();
					logger.info("Keeping rebase commit {} on {}", commit.getHeadTimestamp(), branchPath);
					continue;
				}
				commitsToRollback.add(commit);
			}
		} else {
			// If never released roll back all versions
			commitsToRollback = branchService.findAllVersions(branchPath, Pageable.unpaged()).stream().sorted(Comparator.comparing(Branch::getStart)).collect(Collectors.toList());
		}

		// Roll back in reverse order (i.e the most recent first)
		Collections.reverse(commitsToRollback);
		rollbackCommits(branchPath, commitsToRollback);
	}

	private void rollbackCommits(String path, List<Branch> rollbackList) {
		logger.info("{} branch commits found to roll back on {}.", rollbackList.size(), path);
		List<Class<? extends DomainEntity>> domainTypes = new ArrayList<>(domainEntityConfiguration.getAllDomainEntityTypes());
		for (Branch branchVersion : rollbackList) {
			branchService.rollbackCompletedCommit(branchVersion, domainTypes);
		}
	}

	private String getNewDailyBuildIfExists(CodeSystem codeSystem, long lastImportTimepoint) {
		String deltaDirectoryPath = ResourcePathHelper.getFullPath(dailyBuildResourceConfig, codeSystem.getShortName());
		logger.debug("Daily build resources path '{}'.", deltaDirectoryPath);
		List<String> archiveFilenames = new ArrayList<>();
		try {
			Resource[] deltaArchives = resourcePatternResolver.getResources(deltaDirectoryPath + "/" + "*.zip");
			logger.debug("Found total builds {} from {}",  deltaArchives.length, deltaDirectoryPath);
			for (Resource deltaArchive : deltaArchives) {
				String filename = deltaArchive.getFilename();
				if (filename != null && filename.endsWith(".zip")) {
					// Strip off file separators
					if (filename.contains("/")) {
						filename = filename.substring(filename.lastIndexOf("/") + 1);
					}
					// Check the uploaded time after the last import
					if (isAfterAndNotFuture(filename, lastImportTimepoint)) {
						archiveFilenames.add(filename);
					}
				}
			}
		} catch (FileNotFoundException e) {
			logger.info("No daily builds found from '{}'.", deltaDirectoryPath);
		} catch (IOException e) {
			logger.error("Failed to fetch delta archives from '{}'.", deltaDirectoryPath, e);
		}

		// Get the most recent build for today
		Collections.sort(archiveFilenames);
		Collections.reverse(archiveFilenames);
		if (!archiveFilenames.isEmpty()) {
			String mostRecentBuild = archiveFilenames.iterator().next();
			if (archiveFilenames.size() > 1) {
				logger.info("Found total {} daily builds. '{}' will be loaded.", archiveFilenames.size(), mostRecentBuild);
			}
			return mostRecentBuild;
		}
		return null;
	}

	private boolean isAfterAndNotFuture(String filename, long timestamp) {
		String dateStr = filename.substring(0, filename.lastIndexOf("."));
		SimpleDateFormat formatter = new SimpleDateFormat(DAILY_BUILD_DATE_FORMAT);
		try {
			Date buildDate = formatter.parse(dateStr);
			if (buildDate.before(new Date()) && buildDate.after(new Date(timestamp))) {
				return true;
			}
		} catch (ParseException e) {
			logger.error("File name contains invalid date format expected '{}' but is '{}'.", DAILY_BUILD_DATE_FORMAT, dateStr);
		}
		return false;
	}

	void setResourceManager(ResourceManager resourceManager) {
		this.resourceManager = resourceManager;
	}

	private static class ResourcePathHelper {

		static String getFullPath(ResourceConfiguration resourceConfiguration, String relativePath) {
			if (resourceConfiguration.isUseCloud()) {
				return "s3://" + resourceConfiguration.getCloud().getBucketName()
						+ "/" + getPathAndRelative(resourceConfiguration.getCloud().getPath(), relativePath);
			} else {
				return getPathAndRelative(resourceConfiguration.getLocal().getPath(), relativePath);
			}
		}
		private static String getPathAndRelative(String path, String relativePath) {
			if (!path.isEmpty() && !path.endsWith("/")) {
				path = path + "/";
			}
			if (relativePath.startsWith("/")) {
				relativePath = relativePath.substring(1);
			}
			return path + relativePath;
		}
	}
}
