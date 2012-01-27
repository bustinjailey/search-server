package org.musicbrainz.search.servlet;

import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.search.*;
import org.musicbrainz.search.index.ArtistIndexField;

import java.util.HashMap;
import java.util.Map;

public class ArtistDismaxSearch extends ArtistSearch {

    private DismaxSearcher dismaxSearcher;

    protected void initDismaxSearcher()
    {
        Map<String, Float> fieldBoosts = new HashMap<String, Float>(3);
        fieldBoosts.put(ArtistIndexField.SORTNAME.getName(), 0.8f);
        fieldBoosts.put(ArtistIndexField.ARTIST.getName(), 1.6f);
        fieldBoosts.put(ArtistIndexField.ALIAS.getName(), 0.4f);
        DismaxQueryParser.DismaxAlias dismaxAlias = new DismaxQueryParser.DismaxAlias();
        dismaxAlias.setFields(fieldBoosts);
        dismaxAlias.setTie(0.1f);
        dismaxSearcher = new DismaxSearcher(dismaxAlias);
    }

    /**
     * Standard Search
     *
     * @param searcher
     * @throws Exception
     */
    public ArtistDismaxSearch(IndexSearcher searcher) throws Exception {
        super(searcher);
        initDismaxSearcher();
    }

    /**
     * User By Search All
     *
     * @param searcher
     * @param query
     * @param offset
     * @param limit
     * @throws Exception
     */
    public ArtistDismaxSearch(IndexSearcher searcher, String query, int offset, int limit) throws Exception {
        super(searcher, query, offset, limit);
        initDismaxSearcher();
    }

    protected Query parseQuery(String query) throws ParseException
    {
        return dismaxSearcher.parseQuery(query, analyzer);
    }
}