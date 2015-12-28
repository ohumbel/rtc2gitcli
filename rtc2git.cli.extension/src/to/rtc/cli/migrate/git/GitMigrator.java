package to.rtc.cli.migrate.git;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Formatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import to.rtc.cli.migrate.ChangeSet;
import to.rtc.cli.migrate.ChangeSet.WorkItem;
import to.rtc.cli.migrate.Migrator;
import to.rtc.cli.migrate.Tag;
import to.rtc.cli.migrate.util.Files;
import to.rtc.cli.migrate.util.JazzignoreTranslator;

/**
 * Git implementation of a {@link Migrator}.
 *
 * @author otmar.humbel
 * @author patrick.reinhart
 */
public final class GitMigrator implements Migrator {
	static final List<String> ROOT_IGNORED_ENTRIES = Arrays.asList("/.jazz5", "/.jazzShed", "/.metadata");
	static final Pattern GITIGNORE_PATTERN = Pattern.compile("(^.*(/|))\\.gitignore$");
	static final Pattern JAZZIGNORE_PATTERN = Pattern.compile("(^.*(/|))\\.jazzignore$");
	static final Pattern VALUE_PATTERN = Pattern.compile("^([0-9]+) *(m|mb|k|kb|)$", Pattern.CASE_INSENSITIVE);

	private final Charset defaultCharset;
	private final Set<String> ignoredFileExtensions;
	private final WindowCacheConfig WindowCacheConfig;

	private Git git;
	private Properties properties;
	private PersonIdent defaultIdent;
	private File rootDir;

	public GitMigrator(Properties properties) {
		defaultCharset = Charset.forName("UTF-8");
		ignoredFileExtensions = new HashSet<String>();
		WindowCacheConfig = new WindowCacheConfig();
		initialize(properties);
	}

	private Charset getCharset() {
		return defaultCharset;
	}

	private void initRootGitignore(File sandboxRootDirectory) throws IOException {
		Set<String> ignoreEntries = new LinkedHashSet<String>(ROOT_IGNORED_ENTRIES);
		parseElements(properties.getProperty("global.gitignore.entries", ""), ignoreEntries);
		initRootFile(new File(sandboxRootDirectory, ".gitignore"), ignoreEntries);
	}

	private void initRootGitattributes(File sandboxRootDirectory) throws IOException {
		initRootFile(new File(sandboxRootDirectory, ".gitattributes"), getGitattributeLines());
	}

	private void initRootFile(File rootFile, Collection<String> linesToAdd) throws IOException {
		Charset charset = getCharset();
		List<String> existingLines = Files.readLines(rootFile, charset);
		addMissing(existingLines, linesToAdd);
		if (!existingLines.isEmpty()) {
			Files.writeLines(rootFile, existingLines, charset, false);
		}
	}

	private void parseElements(String elementString, Collection<String> consumer) {
		if (elementString == null || elementString.isEmpty()) {
			return;
		}
		String[] splitted = elementString.split(";");
		int splittedLength = splitted.length;
		for (int i = 0; i < splittedLength; i++) {
			consumer.add(splitted[i].trim());
		}
	}

	void addMissing(Collection<String> existing, Collection<String> adding) {
		for (String entry : adding) {
			if (!existing.contains(entry)) {
				existing.add(entry);
			}
		}
	}

	String getCommitMessage(String workItemText, String comment) {
		return String.format(properties.getProperty("commit.message.format", "%1s %2s"), workItemText, comment).trim();
	}

	List<String> getGitattributeLines() {
		List<String> lines = new ArrayList<String>();
		parseElements(properties.getProperty("gitattributes", ""), lines);
		return lines;
	}

	Set<String> getIgnoredFileExtensions() {
		return ignoredFileExtensions;
	}

	WindowCacheConfig getWindowCacheConfig() {
		return WindowCacheConfig;
	}

	String getWorkItemNumbers(List<WorkItem> workItems) {
		if (workItems.isEmpty()) {
			return "";
		}
		final String format = properties.getProperty("rtc.workitem.number.format", "%s");
		final String delimiter = properties.getProperty("rtc.workitem.number.delimiter", " ");
		final StringBuilder sb = new StringBuilder();
		boolean isFirst = true;
		Formatter formatter = new Formatter(sb);
		try {
			for (WorkItem workItem : workItems) {
				if (isFirst) {
					isFirst = false;
				} else {
					sb.append(delimiter);
				}
				formatter.format(format, String.valueOf(workItem.getNumber()));
			}

		} finally {
			formatter.close();
		}
		return sb.toString();
	}

	SortedSet<String> getExistingIgnoredFiles() {
		try {
			TreeSet<String> exstingIgnoredFiles = new TreeSet<String>();
			for (String ignoredFile : Files.readLines(new File(rootDir, ".gitignore"), getCharset())) {
				if (ignoredFile.startsWith("/")) {
					File ignored = new File(rootDir, ignoredFile.substring(1));
					if (ignored.exists() && ignored.isFile()) {
						exstingIgnoredFiles.add(ignoredFile);
					}
				}
			}
			return exstingIgnoredFiles;
		} catch (IOException e) {
			throw new RuntimeException("To process .gitignore", e);
		}
	}

	private void gitCommit(PersonIdent ident, String comment) {
		try {
			// add all untracked files
			Status status = git.status().call();
			if (!status.isClean()) {

				Set<String> toAdd = handleAdded(status);
				Set<String> toRestore = new HashSet<String>();
				Set<String> toRemove = handleRemoved(status, toRestore);

				// execute the git index commands if needed
				if (!toAdd.isEmpty()) {
					AddCommand add = git.add();
					for (String filepattern : toAdd) {
						add.addFilepattern(filepattern);
					}
					add.call();
				}
				if (!toRemove.isEmpty()) {
					RmCommand rm = git.rm();
					for (String filepattern : toRemove) {
						rm.addFilepattern(filepattern);
					}
					rm.call();
				}
				if (!toRestore.isEmpty()) {
					CheckoutCommand checkout = git.checkout();
					for (String filepattern : toRestore) {
						checkout.addPath(filepattern);
					}
					checkout.call();
				}

				// execute commit if something has changed
				if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
					git.commit().setMessage(comment).setAuthor(ident).setCommitter(ident).call();
				}

				// verify the status once again
				Status afterCommitStatus = git.status().call();
				if (!afterCommitStatus.isClean()) {
					throw new RuntimeException("Status is not clean after commit");
				}
			}
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Unable to commit changes", e);
		}
	}

	private Set<String> handleRemoved(Status status, Set<String> toRestore) {
		Set<String> toRemove = new HashSet<String>();
		// go over all deleted files
		for (String removed : status.getMissing()) {
			Matcher matcher = GITIGNORE_PATTERN.matcher(removed);
			if (matcher.matches()) {
				File jazzignore = new File(rootDir, matcher.group(1).concat(".jazzignore"));
				if (jazzignore.exists()) {
					// restore .gitignore files that where deleted if
					// corresponding .jazzignore exists
					toRestore.add(removed);
					continue;
				}
			}
			// adds removed entry to the index
			toRemove.add(removed);
		}
		handleJazzignores(toRemove);
		return toRemove;
	}

	private Set<String> handleAdded(Status status) {
		Set<String> toAdd = new HashSet<String>();
		// go over untracked files
		for (String untracked : status.getUntracked()) {
			// add it to the index
			toAdd.add(untracked);
		}
		// go over modified files
		for (String modified : status.getModified()) {
			// adds a modified entry to the index
			toAdd.add(modified);
		}
		handleGlobalFileExtensions(toAdd);
		handleJazzignores(toAdd);
		return toAdd;
	}

	private void handleJazzignores(Set<String> relativeFileNames) {
		try {
			Set<String> additionalNames = new HashSet<String>();
			for (String relativeFileName : relativeFileNames) {
				Matcher matcher = JAZZIGNORE_PATTERN.matcher(relativeFileName);
				if (matcher.matches()) {
					File jazzIgnore = new File(rootDir, relativeFileName);
					String gitignoreFile = matcher.group(1).concat(".gitignore");
					if (jazzIgnore.exists()) {
						// change/add case
						List<String> ignoreContent = JazzignoreTranslator.toGitignore(jazzIgnore);
						Files.writeLines(new File(rootDir, gitignoreFile), ignoreContent, getCharset(), false);
					} else {
						// delete case
						new File(rootDir, gitignoreFile).delete();
					}
					additionalNames.add(gitignoreFile);
				}
			}
			// add additional modified name
			relativeFileNames.addAll(additionalNames);
		} catch (IOException e) {
			throw new RuntimeException("Unable to handle .jazzignore", e);
		}
	}

	private void handleGlobalFileExtensions(Set<String> addToGitIndex) {
		Set<String> gitignoreEntries = new LinkedHashSet<String>();
		for (String extension : getIgnoredFileExtensions()) {
			for (Iterator<String> candidateIt = addToGitIndex.iterator(); candidateIt.hasNext();) {
				String addCandidate = candidateIt.next();
				if (addCandidate.endsWith(extension)) {
					gitignoreEntries.add("/".concat(addCandidate));
					candidateIt.remove();
				}
			}
		}
		if (!gitignoreEntries.isEmpty()) {
			try {
				Files.writeLines(new File(rootDir, ".gitignore"), gitignoreEntries, getCharset(), true);
				addToGitIndex.add(".gitignore");
			} catch (IOException e) {
				throw new RuntimeException("Unable to handle .gitignore", e);
			}
		}
	}

	private void initConfig() throws IOException {
		StoredConfig config = git.getRepository().getConfig();
		config.setBoolean("core", null, "ignoreCase", false);
		config.save();
	}

	private int getFactor(String sign) {
		if (!sign.isEmpty()) {
			switch (sign.charAt(0)) {
			case 'k':
			case 'K':
				return org.eclipse.jgit.storage.file.WindowCacheConfig.KB;
			case 'm':
			case 'M':
				return org.eclipse.jgit.storage.file.WindowCacheConfig.MB;
			}
		}
		return 1;
	}

	long parseConfigValue(String value, long defaultValue) {
		if (value != null) {
			Matcher matcher = VALUE_PATTERN.matcher(value.trim());
			if (matcher.matches()) {
				return getFactor(matcher.group(2)) * Long.parseLong(matcher.group(1));
			}
		}
		return defaultValue;
	}

	void initialize(Properties props) {
		properties = props;
		defaultIdent = new PersonIdent(props.getProperty("user.name", "RTC 2 git"),
				props.getProperty("user.email", "rtc2git@rtc.to"));
		parseElements(props.getProperty("ignore.file.extensions", ""), ignoredFileExtensions);
		// update window cache config
		WindowCacheConfig cfg = getWindowCacheConfig();
		cfg.setPackedGitOpenFiles(
				(int) parseConfigValue(props.getProperty("packedgitopenfiles"), cfg.getPackedGitOpenFiles()));
		cfg.setPackedGitLimit(parseConfigValue(props.getProperty("packedgitlimit"), cfg.getPackedGitLimit()));
		cfg.setPackedGitWindowSize(
				(int) parseConfigValue(props.getProperty("packedgitwindowsize"), cfg.getPackedGitWindowSize()));
		cfg.setPackedGitMMAP(Boolean.parseBoolean(props.getProperty("packedgitmmap")));
		cfg.setDeltaBaseCacheLimit(
				(int) parseConfigValue(props.getProperty("deltabasecachelimit"), cfg.getDeltaBaseCacheLimit()));
		long sft = parseConfigValue(props.getProperty("streamfilethreshold"), cfg.getStreamFileThreshold());
		cfg.setStreamFileThreshold(getMaxFileThresholdValue(sft, Runtime.getRuntime().maxMemory()));
	}

	int getMaxFileThresholdValue(long configThreshold, final long maxMem) {
		// don't use more than 1/4 of the heap
		configThreshold = Math.min(configThreshold, maxMem / 4);
		// cannot exceed array length
		configThreshold = Math.min(configThreshold, Integer.MAX_VALUE);
		return (int) configThreshold;
	}

	String createTagName(String tagName) {
		return tagName.replace(' ', '_');
	}

	@Override
	public void init(File sandboxRootDirectory) {
		rootDir = sandboxRootDirectory;
		try {
			File bareGitDirectory = new File(sandboxRootDirectory, ".git");
			if (bareGitDirectory.exists()) {
				git = Git.open(sandboxRootDirectory);
			} else if (sandboxRootDirectory.exists()) {
				git = Git.init().setDirectory(sandboxRootDirectory).call();
			} else {
				throw new RuntimeException(bareGitDirectory + " does not exist");
			}
			getWindowCacheConfig().install();
			initRootGitignore(sandboxRootDirectory);
			initRootGitattributes(sandboxRootDirectory);
			initConfig();
			gitCommit(new PersonIdent(defaultIdent, System.currentTimeMillis(), 0), "Initial commit");
		} catch (IOException e) {
			throw new RuntimeException("Unable to initialize GIT repository", e);
		} catch (GitAPIException e) {
			throw new RuntimeException("Unable to initialize GIT repository", e);
		}
	}

	@Override
	public void close() {
		if (git != null) {
			try {
				git.gc().call();
			} catch (GitAPIException e) {
				e.printStackTrace();
			} finally {
				git.close();
			}
		}
		SortedSet<String> existingIgnoredFiles = getExistingIgnoredFiles();
		if (!existingIgnoredFiles.isEmpty()) {
			System.err.println("Some ignored files still exist in the sandbox:");
			for (String existingIgnoredEntry : existingIgnoredFiles) {
				System.err.println(existingIgnoredEntry);
			}
		}
	}

	@Override
	public void commitChanges(ChangeSet changeset) {
		gitCommit(new PersonIdent(changeset.getCreatorName(), changeset.getEmailAddress(), changeset.getCreationDate(),
				0), getCommitMessage(getWorkItemNumbers(changeset.getWorkItems()), changeset.getComment()));
	}

	@Override
	public void createTag(Tag tag) {
		String tagName = tag.getName();
		if (tagName != null && !tagName.isEmpty()) {
			try {
				git.tag().setTagger(defaultIdent).setName(createTagName(tagName)).call();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException("Unable to tag", e);
			}
		}
	}
}
