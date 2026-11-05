package gov.nasa.pds.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

	/** One part of an identifier: the characters PDS4 allows in a LID. */
	private static final Pattern PART = Pattern.compile("[a-z0-9._-]+");

	/** The number of parts of a bundle LID (urn, agency, archive and bundle), which has no parent. */
	private static final int BUNDLE_PARTS = 4;

	/** No LID comes near this length; longer text is never a LID query. */
	private static final int MAX_LENGTH = 1024;

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

		String text = query.trim().toLowerCase(Locale.ROOT);
		if (text.length() > MAX_LENGTH) {
			return null;
		}
		boolean prefix = text.endsWith("*");
		while (text.endsWith("*")) {
			text = text.substring(0, text.length() - 1);
		}

		int versionStart = text.indexOf("::");
		if (versionStart >= 0) {
			if (!isVersion(text.substring(versionStart + 2))) {
				return null;
			}
			text = text.substring(0, versionStart);
		}

		if (!isLid(text)) {
			return null;
		}
		return new LidQuery(text, prefix);
	}

	/** Whether the text is urn followed by at least three more parts of the allowed characters. */
	private static boolean isLid(String text) {
		String[] parts = text.split(":", -1);
		if (parts.length < BUNDLE_PARTS || !"urn".equals(parts[0])) {
			return false;
		}
		for (int i = 1; i < parts.length; i++) {
			if (!PART.matcher(parts[i]).matches()) {
				return false;
			}
		}
		return true;
	}

	/** Whether the text is a version_id: numbers separated by single dots, such as 1.0 or 12.3.1. */
	private static boolean isVersion(String text) {
		boolean digitSeen = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c >= '0' && c <= '9') {
				digitSeen = true;
			} else if (c == '.' && digitSeen) {
				digitSeen = false;
			} else {
				return false;
			}
		}
		return digitSeen;
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
