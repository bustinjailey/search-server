package org.musicbrainz.search.replication;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.apache.commons.lang.time.StopWatch;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.jdom.Element;
import org.jdom.filter.Filter;
import org.jdom.input.SAXBuilder;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.musicbrainz.search.MbDocument;
import org.musicbrainz.search.index.AnnotationIndex;
import org.musicbrainz.search.index.ArtistIndex;
import org.musicbrainz.search.index.DatabaseIndex;
import org.musicbrainz.search.index.IndexOptions;
import org.musicbrainz.search.index.LabelIndex;
import org.musicbrainz.search.index.MetaIndexField;
import org.musicbrainz.search.index.RecordingIndex;
import org.musicbrainz.search.index.ReleaseGroupIndex;
import org.musicbrainz.search.index.ReleaseIndex;
import org.musicbrainz.search.index.TagIndex;
import org.musicbrainz.search.index.WorkIndex;
import org.musicbrainz.search.replication.packet.ReplicationChange;
import org.musicbrainz.search.replication.packet.ReplicationPacket;

public class LiveDataFeedIndexUpdater {

	private Map<String, EntityDependencyTree> dependencyTrees;
	private final Logger LOGGER = Logger.getLogger(LiveDataFeedIndexUpdater.class.getName());
	private Connection mainDbConn;
	
	public LiveDataFeedIndexUpdater() {
		dependencyTrees = initDependencyTrees();
		LOGGER.setUseParentHandlers(false);
	    Handler conHdlr = new ConsoleHandler();
	    conHdlr.setFormatter(new Formatter() {
	      @Override
	      public String format(LogRecord record) {
	        return new Date(record.getMillis()).toString() + " - "
	        	+ record.getLevel() + " : "
//	            + record.getSourceClassName() + " -:- "
//	            + record.getSourceMethodName() + " -:- "
	            + record.getMessage() + "\n";
	      }
	    });
	    LOGGER.addHandler(conHdlr);
		LOGGER.setLevel(Level.ALL);
	}
	
	public void start(LiveDataFeedIndexOptions options) throws SQLException, IOException {
		
        // Connect to main database
        mainDbConn = options.getMainDatabaseConnection();
    
        StopWatch clock = new StopWatch();

        // MusicBrainz data indexing
        DatabaseIndex[] indices = {
                new ArtistIndex(mainDbConn),
                new ReleaseIndex(mainDbConn),
                new ReleaseGroupIndex(mainDbConn),
                new RecordingIndex(mainDbConn),
                new LabelIndex(mainDbConn),
                new WorkIndex(mainDbConn),
                new AnnotationIndex(mainDbConn),
                new TagIndex(mainDbConn),
        };

        for (DatabaseIndex index : indices) {

            // Check if this index should be built
            if (!options.buildIndex(index.getName())) {
            	LOGGER.info("Skipping index: " + index.getName());
                continue;
            }

            clock.start();
            updateDatabaseIndex(index, options);
            clock.stop();
            LOGGER.info("  Finished in " + Float.toString(clock.getTime()/1000) + " seconds");
            clock.reset();
        }

	}
	
	/**
     * Update an index built from database using Data Replication packets
     * 
     * @param options
     * @throws IOException 
     * @throws SQLException 
     */
    private void updateDatabaseIndex(DatabaseIndex index, LiveDataFeedIndexOptions options) throws IOException, SQLException
    {
    	LOGGER.info("Updating index: " + index.getName());
    	
    	String path = options.getIndexesDir() + index.getFilename();
        IndexWriter indexWriter = new IndexWriter(FSDirectory.open(new File(path)), index.getAnalyzer(), false, IndexWriter.MaxFieldLength.LIMITED);
        //indexWriter.setMaxBufferedDocs(MAX_BUFFERED_DOCS);
        //indexWriter.setMergeFactor(MERGE_FACTOR);
    	
    	IndexReader reader = IndexReader.open(new MMapDirectory(new File(path)));
    	IndexSearcher searcher = new IndexSearcher(reader);
    	
		Term term = new Term(MetaIndexField.META.getName(), MetaIndexField.META_VALUE);
		TermQuery query = new TermQuery(term);
    	TopDocs hits = searcher.search(query, 10);

    	if (hits.scoreDocs.length == 0) {
            throw new IllegalArgumentException("No matches in the index for the given Term.");
    	} else if (hits.scoreDocs.length > 1) {
            throw new IllegalArgumentException("Given Term matches more than 1 document in the index.");
    	} else {
            int docId = hits.scoreDocs[0].doc;

            // retrieve the old document
            MbDocument doc = new MbDocument(searcher.doc(docId));
            int lastReplicationSequence = Integer.parseInt(doc.get(MetaIndexField.REPLICATION_SEQUENCE));
            int lastSchemaSequence = Integer.parseInt(doc.get(MetaIndexField.SCHEMA_SEQUENCE));
            
            Set<Integer> deletedIds = new HashSet<Integer>();
            Set<Integer> insertedIds = new HashSet<Integer>();
            
            // TODO: for debug
            lastReplicationSequence = 1;
            
            // Load and process each replication packet released since
            ReplicationPacket packet;
            while ( (packet = ReplicationPacket.loadFromRepository(lastReplicationSequence+1, options.getRepositoryPath())) != null ) {
            	
            	LOGGER.info("Loading packet #" + packet.getReplicationSequence());
            	
            	/*
            	if (lastSchemaSequence != packet.getSchemaSequence()) {
            		System.err.println("Aborting, new packet is for a different SCHEMA sequence");
            		break;
            	}
            	*/
//            	try {
            	processReplicationPacket(packet, dependencyTrees.get(index.getName()), insertedIds, deletedIds);
//            	} catch(Exception e) {
//            		//TODO: temporary
//            	}
            	
            	lastReplicationSequence++;
            }

            // We're done parsing all replication packets and analyzing impacted entities
            Date indexingDate = new Date();
            LOGGER.info("Started Building index: " + path + " at " + indexingDate);
            
            // Delete obsolete documents
        	for(Integer id : deletedIds) {
        		LOGGER.finer("Deleting document " + id.toString());
        		term = new Term(index.getIdentifierField().getName(), id.toString());
        		query = new TermQuery(term);
        		indexWriter.deleteDocuments(query);
        	}
        	
        	// Index new (or udpated) ones
        	index.init(indexWriter);
        	for(Integer id : insertedIds) {
        		LOGGER.finer("Reindexing document " + id.toString());
        		index.indexData(indexWriter, id, id);
        	}
        	index.destroy();
        	
            index.updateMetaInformation(indexWriter, lastReplicationSequence, lastSchemaSequence, indexingDate);
            indexWriter.commit();
            indexWriter.optimize();
            indexWriter.close();
            
            //For debugging to check sql is not creating too few/many rows
            if(true) {
                int dbRows = index.getNoOfRows(Integer.MAX_VALUE);
                IndexReader newReader = IndexReader.open(FSDirectory.open(new File(path)),true);
                System.out.println("  "+dbRows+" rows in database, "+(newReader.maxDoc() - 1)+" lucene documents");
                reader.close();
            }
    	}
    	
    	/*
        indexWriter = new ThreadedIndexWriter(FSDirectory.open(new File(path)),
                index.getAnalyzer(),
                true,
                Runtime.getRuntime().availableProcessors(),
                options.getDatabaseChunkSize(),
                IndexWriter.MaxFieldLength.LIMITED);

        indexWriter.setMaxBufferedDocs(MAX_BUFFERED_DOCS);
        indexWriter.setMergeFactor(MERGE_FACTOR);
        */

    }
    
	private void processReplicationPacket(ReplicationPacket packet, EntityDependencyTree dependencyTree, Set<Integer> insertedIds, Set<Integer> deletedIds) 
    throws SQLException 
    {
    	
    	Map<String, Set<Integer>> deletedToBeDetermined = new HashMap<String, Set<Integer>>();
    	Map<String, Set<Integer>> insertedToBeDetermined = new HashMap<String, Set<Integer>>();
    	
    	// Initialize deleted and inserted maps of 
    	for (String tableName : dependencyTree.getTables()) {
    		if (tableName.equals(dependencyTree.getRootTableName())) {
    			continue;
    		}
    		deletedToBeDetermined.put(tableName, new HashSet<Integer>());
    		insertedToBeDetermined.put(tableName, new HashSet<Integer>());
    	}
    	
    	// Process changes in replication packet
    	for (ReplicationChange change : packet.getChanges()) {
    		
    		if (!dependencyTree.getTables().contains(change.getTableName())) {
    			LOGGER.finest("Skipping change on table " + change.getTableName());
    			continue;
    		}
    		
    		LinkedTable lt = dependencyTree.getDependency(change.getTableName());
    		
    		LOGGER.finest("Analyzing change #" + change.getId() + " on table " + change.getTableName().toUpperCase());
    		switch (change.getOperation()) {
    			case INSERT:
        			{
        				Map<String,String> values = change.getNewValues();
        				if (lt.isHead()) {
        					insertedIds.add( Integer.parseInt(values.get("id")) );
        				} else {
        					insertedToBeDetermined.get(change.getTableName()).add( 
        							Integer.parseInt(values.get(lt.getSourceJoinField()) )); 
        				}
        			}
        			break;
    			case UPDATE:
        			{
        				Map<String,String> oldValues = change.getOldValues();
        				Map<String,String> newValues = change.getNewValues();
        				if (lt.isHead()) {
        					// TODO: fix hardcoding of "id"?
        					insertedIds.add( Integer.parseInt(newValues.get("id")) );
        					deletedIds.add( Integer.parseInt(oldValues.get("id")) );
        				} else {
        					insertedToBeDetermined.get(change.getTableName()).add( 
        							Integer.parseInt(newValues.get(lt.getSourceJoinField()) ));
        					deletedToBeDetermined.get(change.getTableName()).add( 
        							Integer.parseInt(oldValues.get(lt.getSourceJoinField()) )); 
        				}
        			}
    				break;
    			case DELETE:
        			{
        				Map<String,String> values = change.getOldValues();
        				if (lt.isHead()) {
        					deletedIds.add( Integer.parseInt(values.get("id")) );
        				} else {
        					deletedToBeDetermined.get(change.getTableName()).add( 
        							Integer.parseInt(values.get(lt.getSourceJoinField()) )); 
        				}
        			}
    				break;
    		
    		}
    		
    	}
    	
    	// Now determine the ids of our entity
    	for (String tableName : deletedToBeDetermined.keySet()) {
    		
    		Set<Integer> tmpIds = deletedToBeDetermined.get(tableName);
    		if (tmpIds.isEmpty()) {
    			continue;
    		}
    		LinkedTable lt = dependencyTree.getDependency(tableName);
    		String sql = lt.generateSQL(tmpIds);
    		
    		LOGGER.info("Resolution of deleted ids for table " + tableName + ": " + sql);
    		
    		Statement st = mainDbConn.createStatement();
    		ResultSet rs = st.executeQuery(sql);
    		
    		while (rs.next()) {
    			deletedIds.add(rs.getInt(1));
    		}
    		
    	}
    	
    	for (String tableName : insertedToBeDetermined.keySet()) {
    		
    		Set<Integer> tmpIds = insertedToBeDetermined.get(tableName);
    		if (tmpIds.isEmpty()) {
    			continue;
    		}
    		LinkedTable lt = dependencyTree.getDependency(tableName);
    		String sql = lt.generateSQL(tmpIds);
    		
    		LOGGER.finest("Resolution of inserted ids for table " + tableName + ": " + sql);
    		
    		Statement st = mainDbConn.createStatement();
    		ResultSet rs = st.executeQuery(sql);
    		
    		while (rs.next()) {
    			insertedIds.add(rs.getInt(1));
    		}
    		
    	}
    	
    }
    
	/*
    private void deleteOldDocuments(DatabaseIndex index, IndexWriter indexWriter, Set<Integer> deletedIds) throws CorruptIndexException, IOException {
    	Term term;
    	TermQuery query;
    	for(Integer id : deletedIds) {
    		term = new Term("id", id.toString());
    		query = new TermQuery(term);
    		indexWriter.deleteDocuments(query);
    	}
    }
    
    private void insertNewDocuments(DatabaseIndex index, IndexWriter indexWriter, Set<Integer> insertedIds) throws SQLException, IOException {
    	for(Integer id : insertedIds) {
    		index.indexData(indexWriter, id, id);
    	}
    	
    }
    */
	
    /**
     * Load table dependencies declaration from an XML configuration file
     * @return
     */
    
    private Map<String, EntityDependencyTree> initDependencyTrees() {
    	
    	HashMap<String, EntityDependencyTree> map = new HashMap<String, EntityDependencyTree>();
    	
    	org.jdom.Document document;
    	
    	SAXBuilder sxb = new SAXBuilder();
		try {
			document = sxb.build(new File(getClass().getResource("/dependencies.xml").getFile()));
		} catch (Exception e) {
			System.err.println(e.getMessage());
			return map;
		}

		// Define the filter that allows to get the beginning of the path
		// (i.e. the deepest <table> element)
		Filter filter = new Filter() {
			public boolean matches(Object ob) {
				if (!(ob instanceof Element)) { return false; }
				Element element = (Element) ob;
				if ("table".equals(element.getName()) && element.getChildren().isEmpty()) {
					return true;
				} 
				return false;
			}
		};

		// Iterate over each group
		Element root = document.getRootElement();
		List groups = root.getChildren("group");
		
		for (Iterator it = groups.iterator(); it.hasNext();) {
			
			Element group = (Element) it.next();
			EntityDependencyTree dependencyTree = new EntityDependencyTree();
			String entity = group.getAttributeValue("root_table");
			dependencyTree.setRootTableName(entity);
			map.put(entity, dependencyTree);
			
			System.out.println("Building tree dependency for " + entity + "...");
			
			// Iterate over each path for the group 
			for (Iterator it2 = group.getDescendants(filter); it2.hasNext();) {

				Element currElement = (Element) it2.next();
				
				LinkedTable firstLinkedtable = new LinkedTable(currElement.getAttributeValue("name"));
				LinkedTable lastLinkedTable = firstLinkedtable;
				
				Element lastJoin = currElement.getParentElement();
				currElement = currElement.getParentElement().getParentElement();
				while ( "table".equals(currElement.getName()) ) {
					
					LinkedTable newlt = new LinkedTable(currElement.getAttributeValue("name"));
					// targetJoinKey -> src_field and sourceJoinKey -> target_field
					// because we're reading it the other way
					String targetJoinKey = lastJoin.getAttributeValue("src_field");
					String sourceJoinKey = lastJoin.getAttributeValue("target_field");
					
					lastLinkedTable.setTargetTable(newlt, targetJoinKey, sourceJoinKey);
					lastLinkedTable = newlt;
					lastJoin = currElement.getParentElement();
					currElement = lastJoin.getParentElement();
				}
				
				// Finally declare the whole path
				dependencyTree.addDependency(firstLinkedtable);
			}
			
			// Output of our dependency for debug purpose
			
			for (LinkedTable lt : dependencyTree.dependencies.values()) {
				if (lt.isHead()) continue;
				System.out.print(entity + " ==> ");
				while (lt.getTargetTable() != null) {
					System.out.print(lt.getTableName() + " (" + lt.getSourceJoinField() + 
						") -> " + lt.getTargetTable().getTableName() + " (" + lt.getTargetJoinField() + ")");
					lt = lt.getTargetTable();
					if (lt.getTargetTable() != null) System.out.print(" -> ");
				}
				System.out.println("");
			}
			
		}
    	
    	return map;
    }

	/**
	 * @param args
	 * @throws SQLException 
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException, SQLException {
		
		LiveDataFeedIndexOptions options = new LiveDataFeedIndexOptions();
        CmdLineParser parser = new CmdLineParser(options);

        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println("Couldn't parse command line parameters");
            parser.printUsage(System.out);
            System.exit(1);
        }

        // On request, print command line usage
        if (options.isHelp()) {
            parser.printUsage(System.out);
            System.exit(1);
        }
        
        // At least one index should have been selected 
        ArrayList<String> selectedIndexes = options.selectedIndexes();
        if (selectedIndexes.size() == 0 
              || (selectedIndexes.size() == 1 && selectedIndexes.contains(""))) { 
            System.out.println("No indexes selected. Exiting.");
            System.exit(1);
        }
        
        LiveDataFeedIndexUpdater updater = new LiveDataFeedIndexUpdater();
        updater.start(options);
	}
    
}