package de.workaround.account;

import java.util.Map;

/**
 * Shared validation for uploaded images (user avatars and repository images). Enforces a 2 MB limit
 * and an allowlist of content types, each mapped to the leading magic bytes we require the upload to
 * actually start with, so a mislabelled or spoofed file is rejected rather than stored and served
 * back with a wrong type.
 */
public final class ImageValidation
{
	static final long MAX_BYTES = 2L * 1024 * 1024;

	private static final Map<String, byte[]> ALLOWED = Map.of(
		"image/png", new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A },
		"image/jpeg", new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF },
		"image/gif", new byte[] { 'G', 'I', 'F', '8' },
		"image/webp", new byte[] { 'R', 'I', 'F', 'F' });

	// The WebP form type follows the 4-byte "RIFF" tag and a 4-byte size field, i.e. at offset 8.
	private static final byte[] WEBP_FORM_TYPE = new byte[] { 'W', 'E', 'B', 'P' };

	private ImageValidation()
	{
	}

	/**
	 * Validates the uploaded bytes against the declared content type and returns the normalised
	 * (trimmed, lower-cased) content type to persist. Throws {@link InvalidImageException} on any
	 * violation.
	 */
	public static String validate(byte[] bytes, String declaredContentType)
	{
		if (bytes == null || bytes.length == 0)
		{
			throw new InvalidImageException("No image was uploaded.");
		}
		if (bytes.length > MAX_BYTES)
		{
			throw new InvalidImageException("Image is too large (max 2 MB).");
		}
		String contentType = declaredContentType == null ? "" : declaredContentType.trim().toLowerCase();
		byte[] magic = ALLOWED.get(contentType);
		if (magic == null)
		{
			throw new InvalidImageException("Unsupported image type. Use PNG, JPEG, GIF or WebP.");
		}
		if (!startsWith(bytes, magic))
		{
			throw new InvalidImageException("File content does not match its image type.");
		}
		// "RIFF" is a generic container shared by WAV/AVI/…; a real WebP additionally carries the
		// "WEBP" form type at offset 8, so verify it rather than accept any RIFF file as an image.
		if (contentType.equals("image/webp") && !regionMatches(bytes, 8, WEBP_FORM_TYPE))
		{
			throw new InvalidImageException("File content does not match its image type.");
		}
		return contentType;
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix)
	{
		return regionMatches(bytes, 0, prefix);
	}

	private static boolean regionMatches(byte[] bytes, int offset, byte[] expected)
	{
		if (bytes.length < offset + expected.length)
		{
			return false;
		}
		for (int i = 0; i < expected.length; i++)
		{
			if (bytes[offset + i] != expected[i])
			{
				return false;
			}
		}
		return true;
	}

}
