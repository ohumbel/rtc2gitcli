package to.rtc.cli.migrate.git;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import to.rtc.cli.migrate.ChangeSet;

public class GitMigratorTestMain {

	public static void main(String[] args) throws Exception {
		// init
		// Charset cs = Charset.forName("UTF-8");
		Properties props = new Properties();
		props.setProperty("user.email", "john.doe@somewhere.com");
		props.setProperty("user.name", "John Doe");

		GitMigrator migrator = new GitMigrator(props);

		String tmproot = System.getProperty("java.io.tmpfile", "/tmp");
		File basedir = new File(tmproot, "GitMigratorTestMain");
		if (!basedir.exists()) {
			basedir.mkdirs();
		}
		if (!basedir.isDirectory()) {
			throw new RuntimeException(basedir.getAbsolutePath() + " is not a directory");
		}
		migrator.initialize(props);
		migrator.init(basedir);

		int c = 0;
		int commits = 1;
		do {
			ChangeSet changeset = new TestChangeSet(commits++);
			migrator.commitChanges(changeset);
			System.out.println("press ENTER to continue, c + ENTER to cancel... ");
			c = System.in.read();
		} while (c != 'c');
	}

	private static final class TestChangeSet implements ChangeSet {
		private int commit;

		public TestChangeSet(int commit) {
			this.commit = commit;
		}

		@Override
		public String getComment() {
			return "" + commit;
		}

		@Override
		public String getCreatorName() {
			return "Otmar Humbel";
		}

		@Override
		public String getEmailAddress() {
			return "otmar.humbel@bison-group.com";
		}

		@Override
		@SuppressWarnings("deprecation")
		public long getCreationDate() {
			return new Date(2015, 11, 28, 15, 45, 55).getTime();
		}

		@Override
		public List<WorkItem> getWorkItems() {
			return new ArrayList<ChangeSet.WorkItem>();
		}

	}
}
