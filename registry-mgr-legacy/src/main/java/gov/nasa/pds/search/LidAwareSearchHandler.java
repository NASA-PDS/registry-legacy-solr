package gov.nasa.pds.search;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.handler.component.SearchHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QueryUtils;
import org.apache.solr.search.SyntaxError;

/**
 * A search handler that turns a query that is a LID or a LIDVID into a search on the {@code lid} field.
 *
 * <ul>
 * <li>{@code <LID>} or {@code <LID>::<version_id>} finds the product with that LID (the latest version).</li>
 * <li>{@code <LID>*} finds the product and every product whose LID starts with it, the product first.</li>
 * <li>A LID that is not in the index finds its parents instead, nearest first: a product LID finds its collection
 * and its bundle, because products themselves are not searched.</li>
 * </ul>
 *
 * Any other query is passed on unchanged. See {@link LidQuery} for what counts as a LID query.
 */
public class LidAwareSearchHandler extends SearchHandler {
	private static final Logger LOG = Logger.getLogger(LidAwareSearchHandler.class.getName());

	private static final String LID_FIELD = "lid";

	/** Weight of the exact match over the products below it, so it is listed first. */
	private static final int EXACT_BOOST = 100;

	@Override
	public void handleRequestBody(SolrQueryRequest request, SolrQueryResponse response) throws Exception {
		LidQuery lidQuery = LidQuery.parse(request.getParams().get(CommonParams.Q));
		if (lidQuery != null) {
			String query = buildQuery(request, lidQuery);
			LOG.log(Level.INFO, "LID query {0}{1} searched as: {2}",
					new Object[] { lidQuery.getLid(), lidQuery.isPrefix() ? "*" : "", query });

			ModifiableSolrParams params = new ModifiableSolrParams(request.getParams());
			params.set(CommonParams.Q, query);
			request.setParams(params);
		}

		super.handleRequestBody(request, response);
	}

	/**
	 * Builds the query for a LID: the LID itself, or its parents when it is not in the index.
	 */
	private String buildQuery(SolrQueryRequest request, LidQuery lidQuery) throws IOException, SyntaxError {
		String lid = lidQuery.getLid();
		Query lookup = lidQuery.isPrefix() ? new PrefixQuery(new Term(LID_FIELD, lid)) : new TermQuery(new Term(LID_FIELD, lid));

		if (!exists(request, lookup)) {
			List<String> parents = lidQuery.getParentLids();
			if (!parents.isEmpty()) {
				return parentsQuery(parents);
			}
		}

		if (lidQuery.isPrefix()) {
			return LID_FIELD + ":\"" + lid + "\"^" + EXACT_BOOST + " OR " + LID_FIELD + ":" + ClientUtils.escapeQueryChars(lid) + "*";
		}
		return LID_FIELD + ":\"" + lid + "\"";
	}

	/** The parents as one query, the nearest one weighted highest so it is listed first. */
	private static String parentsQuery(List<String> parents) {
		StringBuilder query = new StringBuilder();
		for (int i = 0; i < parents.size(); i++) {
			if (i > 0) {
				query.append(" OR ");
			}
			query.append(LID_FIELD).append(":\"").append(parents.get(i)).append("\"^").append(parents.size() - i);
		}
		return query.toString();
	}

	/**
	 * Whether the index has a product the request would return for this lookup. The request's own filters count
	 * (older versions and the product types a handler leaves out are not found), except the collapse filter, which
	 * only chooses among versions of the same product.
	 */
	private boolean exists(SolrQueryRequest request, Query lookup) throws IOException, SyntaxError {
		BooleanQuery.Builder query = new BooleanQuery.Builder();
		query.add(lookup, BooleanClause.Occur.MUST);

		String[] filters = request.getParams().getParams(CommonParams.FQ);
		if (filters != null) {
			for (String filter : filters) {
				if (filter == null || filter.isBlank() || filter.startsWith("{!collapse")) {
					continue;
				}
				Query filterQuery = QParser.getParser(filter, request).getQuery();
				query.add(QueryUtils.makeQueryable(filterQuery), BooleanClause.Occur.FILTER);
			}
		}
		return request.getSearcher().count(query.build()) > 0;
	}
}
