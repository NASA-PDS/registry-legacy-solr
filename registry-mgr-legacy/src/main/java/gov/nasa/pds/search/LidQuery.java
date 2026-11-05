package gov.nasa.pds.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A keyword query that is a PDS4 logical identifier (LID) or a LID with its version (LIDVID).
 *
 * <p>
 * A query is a LID query when the whole query, once trimmed, is one identifier: {@code urn} followed by at least
 * three colon separated parts made of the characters PDS4 allows in a LID (lowercase letters, digits, {@code -},
 * {@code .} and {@code _}). A bundle LID has four parts, a collection LID five and a product LID six. The
 * {@code ::<version_id>} of a LIDVID is dropped, because only the latest version is searched, and a trailing
 * {@code *} asks for every LID that starts with the given one. The query is lowercased, as LIDs are.
 */
public final class LidQuery {

	/** The identifier: urn and at least three more parts. */
	private static final Pattern LID = Pattern.compile("urn(:[a-z0-9._-]+){3,}");

	/** The version_id of a LIDVID. */
	private static final Pattern VERSION = Pattern.compile("[0-9]+(\\.[0-9]+)*");

	/** The number of parts of a bundle LID, which has no parent. */
	private static final int BUNDLE_PARTS = 4;

	private final String lid;
	private final boolean prefix;

	private LidQuery(String lid, boolean prefix) {
		this.lid = lid;
		this.prefix = prefix;
	}

	/**
	 * Reads a query.
	 *
	 * @param query The query text, which may be null.
	 * @return The LID query, or null when the query is anything other than a single LID or LIDVID.
	 */
	public static LidQuery parse(String query) {
		if (query == null) {
			return null;
		}

		String text = query.trim().toLowerCase();
		boolean prefix = text.endsWith("*");
		while (text.endsWith("*")) {
			text = text.substring(0, text.length() - 1);
		}

		int versionStart = text.indexOf("::");
		if (versionStart >= 0) {
			if (!VERSION.matcher(text.substring(versionStart + 2)).matches()) {
				return null;
			}
			text = text.substring(0, versionStart);
		}

		if (!LID.matcher(text).matches()) {
			return null;
		}
		return new LidQuery(text, prefix);
	}

	/** The LID, without a version, in lowercase. */
	public String getLid() {
		return lid;
	}

	/** Whether the query asked for every LID that starts with this one (it ended with {@code *}). */
	public boolean isPrefix() {
		return prefix;
	}

	/**
	 * The LIDs above this one, nearest first, down to the bundle: a product LID gives its collection and its
	 * bundle, a collection LID gives its bundle and a bundle LID gives none.
	 */
	public List<String> getParentLids() {
		List<String> parents = new ArrayList<String>();
		String parent = lid;
		while (parent.chars().filter(c -> c == ':').count() + 1 > BUNDLE_PARTS) {
			parent = parent.substring(0, parent.lastIndexOf(':'));
			parents.add(parent);
		}
		return parents;
	}
}
