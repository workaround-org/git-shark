package de.workaround.git;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Streams the source tree of a ref as a downloadable archive, the way a release page offers "source code
 * (zip/tar.gz)". Both formats are written with the JDK alone ({@link ZipOutputStream}, a minimal ustar writer
 * plus {@link GZIPOutputStream}) so no extra archiving dependency has to survive the native build. Blobs are
 * copied straight out of the object database, so even large trees never sit in memory as a whole.
 *
 * <p>Submodules (gitlinks) are skipped — they have no content in this repository. Symlinks are written as
 * regular files holding their target path, which is what the tree object stores.
 */
@ApplicationScoped
public class GitArchiveService
{
	private static final int BLOCK = 512;

	public enum Format
	{
		ZIP(".zip", "application/zip"),
		TAR_GZ(".tar.gz", "application/gzip");

		/** The file-name suffix this format is requested and served under. */
		public final String suffix;

		public final String mediaType;

		Format(String suffix, String mediaType)
		{
			this.suffix = suffix;
			this.mediaType = mediaType;
		}
	}

	/**
	 * Writes the tree of {@code ref} into {@code out}, with every entry below a single {@code prefix}
	 * directory (as git's own archive does, so unpacking never litters the current directory).
	 */
	public void write(Path barePath, String ref, String prefix, Format format, OutputStream out)
	{
		try (Repository repo = open(barePath); RevWalk walk = new RevWalk(repo))
		{
			ObjectId id = repo.resolve(ref + "^{commit}");
			if (id == null)
			{
				throw new InvalidReleaseException("'" + ref + "' does not name a branch, tag or commit");
			}
			RevCommit commit = walk.parseCommit(id);
			long modified = commit.getCommitTime() * 1000L;
			if (format == Format.ZIP)
			{
				writeZip(repo, commit, prefix, modified, out);
			}
			else
			{
				writeTarGz(repo, commit, prefix, modified / 1000L, out);
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private static void writeZip(Repository repo, RevCommit commit, String prefix, long modified, OutputStream out)
		throws IOException
	{
		ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
		try (TreeWalk walk = files(repo, commit))
		{
			while (walk.next())
			{
				// gitlinks point at a commit of another repository and have no content to archive
				if (walk.getFileMode() == FileMode.GITLINK)
				{
					continue;
				}
				ObjectLoader loader = repo.open(walk.getObjectId(0));
				ZipEntry entry = new ZipEntry(prefix + "/" + walk.getPathString());
				entry.setTime(modified);
				zip.putNextEntry(entry);
				loader.copyTo(zip);
				zip.closeEntry();
			}
		}
		zip.finish();
	}

	private static void writeTarGz(Repository repo, RevCommit commit, String prefix, long modified, OutputStream out)
		throws IOException
	{
		GZIPOutputStream gzip = new GZIPOutputStream(out);
		try (TreeWalk walk = files(repo, commit))
		{
			while (walk.next())
			{
				if (walk.getFileMode() == FileMode.GITLINK)
				{
					continue;
				}
				ObjectLoader loader = repo.open(walk.getObjectId(0));
				long size = loader.getSize();
				int mode = walk.getFileMode() == FileMode.EXECUTABLE_FILE ? 0755 : 0644;
				gzip.write(header(prefix + "/" + walk.getPathString(), size, mode, modified));
				loader.copyTo(gzip);
				gzip.write(new byte[padding(size)]);
			}
		}
		// a tar stream ends with two zero-filled blocks
		gzip.write(new byte[2 * BLOCK]);
		gzip.finish();
	}

	private static TreeWalk files(Repository repo, RevCommit commit) throws IOException
	{
		TreeWalk walk = new TreeWalk(repo);
		walk.addTree(commit.getTree());
		walk.setRecursive(true);
		return walk;
	}

	private static int padding(long size)
	{
		int remainder = (int) (size % BLOCK);
		return remainder == 0 ? 0 : BLOCK - remainder;
	}

	/** Builds one 512-byte ustar header block for a regular file. */
	private static byte[] header(String path, long size, int mode, long modified)
	{
		byte[] block = new byte[BLOCK];
		String name = path;
		String prefix = "";
		if (name.getBytes(StandardCharsets.UTF_8).length > 100)
		{
			int split = splitPoint(name);
			if (split < 0)
			{
				throw new IllegalStateException("Path too long for a tar archive: " + path);
			}
			prefix = name.substring(0, split);
			name = name.substring(split + 1);
		}
		text(block, 0, 100, name);
		octal(block, 100, 8, mode);
		octal(block, 108, 8, 0);
		octal(block, 116, 8, 0);
		octal(block, 124, 12, size);
		octal(block, 136, 12, modified);
		// the checksum is computed over the header with its own field blank-filled
		for (int i = 148; i < 156; i++)
		{
			block[i] = ' ';
		}
		block[156] = '0';
		text(block, 257, 6, "ustar");
		block[263] = '0';
		block[264] = '0';
		text(block, 265, 32, "root");
		text(block, 297, 32, "root");
		text(block, 345, 155, prefix);
		int checksum = 0;
		for (byte b : block)
		{
			checksum += b & 0xff;
		}
		byte[] digits = pad(Integer.toOctalString(checksum), 6).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(digits, 0, block, 148, 6);
		block[154] = 0;
		block[155] = ' ';
		return block;
	}

	/** The last slash that leaves ≤ 100 bytes of file name and ≤ 155 bytes of prefix, or -1 if there is none. */
	private static int splitPoint(String path)
	{
		for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1))
		{
			int nameBytes = path.substring(slash + 1).getBytes(StandardCharsets.UTF_8).length;
			int prefixBytes = path.substring(0, slash).getBytes(StandardCharsets.UTF_8).length;
			if (nameBytes <= 100 && prefixBytes <= 155)
			{
				return slash;
			}
		}
		return -1;
	}

	private static void text(byte[] block, int offset, int length, String value)
	{
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		System.arraycopy(bytes, 0, block, offset, Math.min(bytes.length, length - 1));
	}

	private static void octal(byte[] block, int offset, int length, long value)
	{
		byte[] digits = pad(Long.toOctalString(value), length - 1).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(digits, 0, block, offset, length - 1);
		block[offset + length - 1] = 0;
	}

	private static String pad(String octal, int width)
	{
		if (octal.length() > width)
		{
			throw new IllegalStateException("Value does not fit a tar header field: " + octal);
		}
		return "0".repeat(width - octal.length()) + octal;
	}

	private static Repository open(Path barePath) throws IOException
	{
		return new FileRepositoryBuilder().setGitDir(barePath.toFile()).setMustExist(true).build();
	}

}
