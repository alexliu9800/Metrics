/**
 * 
 */
package io.github.luzzu.linkeddata.qualitymetrics.commons;

import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.output.StringBuilderWriter;
import org.apache.jena.graph.Node;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFLib;
import org.apache.jena.shared.Lock;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;

import io.github.luzzu.linkeddata.qualitymetrics.commons.cache.LinkedDataMetricsCacheManager;
import io.github.luzzu.qualitymetrics.commons.cache.CachedVocabulary;
import io.github.luzzu.semantics.commons.ResourceCommons;
import io.github.luzzu.semantics.vocabularies.LMI;

/**
 * @author Jeremy Debattista
 * 
 * This helper class loads known vocabularies
 * into a Jena dataset.
 * 
 * In this package, we provide 53 non-propriatary
 * vocabularies that are used by at least 1% of 
 * the whole LOD Cloud. This list is compiled from
 * http://linkeddatacatalog.dws.informatik.uni-mannheim.de/state/#toc6
 * 
 */
public class VocabularyLoader {

	// --- Instance Variables --- //
	private static Logger logger = LoggerFactory.getLogger(VocabularyLoader.class);
	private static volatile VocabularyLoader instance = null;
	private static Object lock = new Object();


	// --- Vocabulary Storage and Cache --- //
	private LinkedDataMetricsCacheManager dcm = LinkedDataMetricsCacheManager.getInstance();
	private Dataset dataset = DatasetFactory.createGeneral();
	private ConcurrentMap<String, String> knownVocabularies = new ConcurrentHashMap<String,String>();
	private ConcurrentMap<String, String> localKnownVocabularies = new ConcurrentHashMap<String,String>();

	
	// --- LRU Caches --- //
    private ConcurrentMap<String, Boolean> termsExists = new ConcurrentLinkedHashMap.Builder<String, Boolean>().maximumWeightedCapacity(10000).build();
	private ConcurrentMap<String, Boolean> isPropertyMap = new ConcurrentLinkedHashMap.Builder<String, Boolean>().maximumWeightedCapacity(10000).build();
    private ConcurrentMap<String, Boolean> objectProperties = new ConcurrentLinkedHashMap.Builder<String, Boolean>().maximumWeightedCapacity(10000).build();
    private ConcurrentMap<String, Boolean> datatypeProperties = new ConcurrentLinkedHashMap.Builder<String, Boolean>().maximumWeightedCapacity(10000).build();
	private ConcurrentMap<String, Boolean> isClassMap = new ConcurrentLinkedHashMap.Builder<String, Boolean>().maximumWeightedCapacity(10000).build();
    private ConcurrentMap<String, Boolean> checkedDeprecatedTerm = new ConcurrentLinkedHashMap.Builder<String, Boolean>().maximumWeightedCapacity(10000).build();
    private ConcurrentMap<String, Set<RDFNode>> propertyDomains = new ConcurrentLinkedHashMap.Builder<String, Set<RDFNode>>().maximumWeightedCapacity(10000).build();
    private ConcurrentMap<String, Set<RDFNode>> propertyRanges = new ConcurrentLinkedHashMap.Builder<String, Set<RDFNode>>().maximumWeightedCapacity(10000).build();
    private ConcurrentMap<String, Set<RDFNode>> parentNodes = new ConcurrentLinkedHashMap.Builder<String, Set<RDFNode>>().maximumWeightedCapacity(10000).build();
    private ConcurrentMap<String, Set<RDFNode>> childNodes = new ConcurrentLinkedHashMap.Builder<String, Set<RDFNode>>().maximumWeightedCapacity(10000).build();
    private ConcurrentMap<String, Boolean> isIFPMap = new ConcurrentLinkedHashMap.Builder<String, Boolean>().maximumWeightedCapacity(10000).build();
    private ConcurrentMap<String, Set<RDFNode>> disjointWith = new ConcurrentLinkedHashMap.Builder<String, Set<RDFNode>>().maximumWeightedCapacity(10000).build();
    
    private ConcurrentMap<String, Boolean> failSafeMap = new ConcurrentLinkedHashMap.Builder<String, Boolean>().maximumWeightedCapacity(10000).build(); // A small fail-safe map that checks whether a domain retrieves vocabs.
    private ConcurrentMap<String, Integer> failSafeCounter = new ConcurrentLinkedHashMap.Builder<String, Integer>().maximumWeightedCapacity(10000).build(); // keeps a counter of the number of times an NS was accessed before putting the NS to the fail-safe map
    private final Integer NS_MAX_RETRIES = 3;

    

	// --- Constructor and Instance --- //
	private VocabularyLoader(){
		knownVocabularies.put("http://dbpedia.org/ontology/","dbpedia.nt");
		knownVocabularies.put("http://www.w3.org/1999/02/22-rdf-syntax-ns#","rdf.rdf");
		knownVocabularies.put("http://www.w3.org/2000/01/rdf-schema#","rdfs.rdf");
		knownVocabularies.put("http://xmlns.com/foaf/0.1/","foaf.rdf");
		knownVocabularies.put("http://purl.org/dc/terms/","dcterm.rdf");
		knownVocabularies.put("http://purl.org/dc/elements/1.1/","dcelements.ttl");
		knownVocabularies.put("http://www.w3.org/2002/07/owl#","owl.rdf");
		knownVocabularies.put("http://www.w3.org/2003/01/geo/wgs84_pos#","pos.rdf");
		knownVocabularies.put("http://rdfs.org/sioc/ns#","sioc.rdf");
//		knownDatasets.put("http://webns.net/mvcb/","admin.rdf");
		knownVocabularies.put("http://www.w3.org/2004/02/skos/core#","skos.rdf");
		knownVocabularies.put("http://rdfs.org/ns/void#","void.rdf"); //TODO update new namespace
		knownVocabularies.put("http://purl.org/vocab/bio/0.1/","bio.rdf");
		knownVocabularies.put("http://purl.org/linked-data/cube#","cube.ttl");
		knownVocabularies.put("http://purl.org/rss/1.0/","rss.rdf");
		knownVocabularies.put("http://www.w3.org/2000/10/swap/pim/contact#","w3con.rdf");
		knownVocabularies.put("http://usefulinc.com/ns/doap#","doap.rdf");
		knownVocabularies.put("http://purl.org/ontology/bibo/","bibo.rdf");
		knownVocabularies.put("http://www.w3.org/ns/dcat#","dcat.rdf");
		knownVocabularies.put("http://www.w3.org/ns/auth/cert#","cert.rdf");
		knownVocabularies.put("http://purl.org/linked-data/sdmx/2009/dimension#","sdmxd.ttl");
		knownVocabularies.put("http://www.daml.org/2001/10/html/airport-ont#","airport.rdf");
		knownVocabularies.put("http://xmlns.com/wot/0.1/","wot.rdf");
//		knownDatasets.put("http://purl.org/rss/1.0/modules/content/","content.rdf");
		knownVocabularies.put("http://creativecommons.org/ns#","cc.rdf");
		knownVocabularies.put("http://purl.org/vocab/relationship/","ref.rdf");
//		knownDatasets.put("http://xmlns.com/wordnet/1.6/","wn.rdf");
		knownVocabularies.put("http://rdfs.org/sioc/types#","tsioc.rdf");
		knownVocabularies.put("http://www.w3.org/2006/vcard/ns#","vcard2006.rdf");
		knownVocabularies.put("http://purl.org/linked-data/sdmx/2009/attribute#","sdmxa.ttl");
		knownVocabularies.put("http://www.geonames.org/ontology#","gn.rdf");
		knownVocabularies.put("http://data.semanticweb.org/ns/swc/ontology#","swc.rdf");
		knownVocabularies.put("http://purl.org/dc/dcmitype/","dctypes.rdf");
		knownVocabularies.put("http://purl.org/net/provenance/ns#","hartigprov.rdf");
		knownVocabularies.put("http://www.w3.org/ns/sparql-service-description#","sd.rdf");
		knownVocabularies.put("http://open.vocab.org/terms/","open.ttl");
		knownVocabularies.put("http://www.w3.org/ns/prov#","prov.rdf");
		knownVocabularies.put("http://purl.org/vocab/resourcelist/schema#","resource.rdf");
		knownVocabularies.put("http://rdvocab.info/elements/","rda.rdf");
		knownVocabularies.put("http://purl.org/net/provenance/types#","prvt.rdf");
		knownVocabularies.put("http://purl.org/NET/c4dm/event.owl#","c4dm.rdf");
		knownVocabularies.put("http://purl.org/goodrelations/v1#","gr.rdf");
		knownVocabularies.put("http://www.w3.org/ns/auth/rsa#","rsa.rdf");
		knownVocabularies.put("http://purl.org/vocab/aiiso/schema#","aiiso.rdf");
		knownVocabularies.put("http://purl.org/net/pingback/","pingback.rdf");
		knownVocabularies.put("http://www.w3.org/2006/time#","time.rdf");
		knownVocabularies.put("http://www.w3.org/ns/org#","org.rdf");
		knownVocabularies.put("http://www.w3.org/2007/05/powder-s#","wdrs.rdf");
		knownVocabularies.put("http://www.w3.org/2003/06/sw-vocab-status/ns#","vs.rdf");
		knownVocabularies.put("http://purl.org/vocab/vann/","vann.rdf");
		knownVocabularies.put("http://www.w3.org/2002/12/cal/icaltzd#","icaltzd.rdf");
		knownVocabularies.put("http://purl.org/vocab/frbr/core#","frbrcore.rdf");
		knownVocabularies.put("http://www.w3.org/1999/xhtml/vocab#","xhv.rdf");
		knownVocabularies.put("http://purl.org/vocab/lifecycle/schema#","lcy.rdf");
		knownVocabularies.put("http://www.w3.org/2004/03/trix/rdfg-1/","rdfg.rdf");
		knownVocabularies.put("http://schema.org/", "schema.rdf"); //added schema.org since it does not allow content negotiation
	}
	
	
	public static VocabularyLoader getInstance(){
		if (instance == null){
			synchronized(lock){
				logger.debug("Creating Instance for Vocabulary Loader");
				instance = new VocabularyLoader();
				instance.checkforLocalVocabs();
			}
		}
		return instance;
	}
	
	// --- Vocabulary Loading Methods --- //
	private void checkforLocalVocabs() {
		Path dir = Paths.get("local-vocabs/");
		Path configFile = Paths.get("local-vocabs/local.ttl");
		
		if (Files.exists(dir)) {
			if (Files.exists(configFile)) {
				logger.info("Loading Vocabularies");
				
				Model m = ModelFactory.createDefaultModel().read(configFile.toString());
				List<Resource> iter = m.listResourcesWithProperty(RDF.type, LMI.LocalVocabulary).toList();
				iter.forEach(r -> {
					Optional<RDFNode> namespace = m.listObjectsOfProperty(r, LMI.namespace).nextOptional();
					Optional<RDFNode> filename = m.listObjectsOfProperty(r, LMI.filename).nextOptional();
					if (namespace.isPresent() && filename.isPresent()) {
						logger.info("Loading vocabulary: "+ filename.get().asLiteral().getString());
						localKnownVocabularies.put(namespace.get().asLiteral().getString(), filename.get().asLiteral().getString());
					}
				});
			}
		}
	}
	
	public void loadVocabulary(String vocabURI){
		if(!(this.dataset.containsNamedModel(vocabURI))) 
			this.loadNStoDataset(vocabURI);
	}

	private synchronized void loadNStoDataset(String ns){
		if (this.knownVocabularies.containsKey(ns)){
			Model m = RDFDataMgr.loadModel("vocabs/" + this.knownVocabularies.get(ns));
			this.dataset.addNamedModel(ns, m);
		} else if (this.localKnownVocabularies.containsKey(ns)) {
			Model m = RDFDataMgr.loadModel("local-vocabs/" + this.localKnownVocabularies.get(ns));
			this.dataset.addNamedModel(ns, m);
		} else {
			//download and store in cache
			if (this.dcm.existsInCache(LinkedDataMetricsCacheManager.VOCABULARY_CACHE, ns)){
				try{
					CachedVocabulary cv = (CachedVocabulary) this.dcm.getFromCache(LinkedDataMetricsCacheManager.VOCABULARY_CACHE, ns);
					StringReader reader = new StringReader(cv.getTextualContent());
					Model m = ModelFactory.createOntologyModel();
					m.read(reader, ns, cv.getLanguage());
					this.dataset.addNamedModel(ns, m);
				}catch (ClassCastException cce){
					logger.error("Cannot cast {} " + ns);
				}
			} else {
				downloadAndLoadVocab(ns);
			}
		}
	}
	
	private synchronized void loadNStoDataset(String ns, Node term){
		if (this.knownVocabularies.containsKey(ns)){
			Model m = RDFDataMgr.loadModel("vocabs/" + this.knownVocabularies.get(ns));
			this.dataset.addNamedModel(ns, m);
		} else {
			//download and store in cache
			if (this.dcm.existsInCache(LinkedDataMetricsCacheManager.VOCABULARY_CACHE, ns)){
				try{
					CachedVocabulary cv = (CachedVocabulary) this.dcm.getFromCache(LinkedDataMetricsCacheManager.VOCABULARY_CACHE, ns);
					StringReader reader = new StringReader(cv.getTextualContent());
					Model m = ModelFactory.createOntologyModel();
					m.read(reader, ns, cv.getLanguage());
					this.dataset.addNamedModel(ns, m);
				}catch (ClassCastException cce){
					logger.error("Cannot cast {} " + ns);
				}
			} else {
				downloadAndLoadVocab(ns, term);
			}
		}
	}
	
	private synchronized void addToFailSafeDecision(String domainAuthority){
		if (this.failSafeCounter.containsKey(domainAuthority)){
			Integer current = this.failSafeCounter.get(domainAuthority) + 1;
			if (current == NS_MAX_RETRIES){
				this.failSafeMap.put(domainAuthority, true);
				this.failSafeCounter.put(domainAuthority, 0);
			} else {
				this.failSafeCounter.put(domainAuthority, current);
			}
		} else {
			this.failSafeCounter.putIfAbsent(domainAuthority, 0);

		}
	}
	
	private synchronized void updateFailSafeCache(String domainAuthority){
		if (this.failSafeCounter.containsKey(domainAuthority)){
				this.failSafeCounter.remove(domainAuthority);
		}	
	}
	
	private synchronized void downloadAndLoadVocab(final String ns) {
		if (ns.startsWith("http://vocab.deri.ie")) return;
		
		URL domURL = null;
		String domAuth = "";
		try {
			domURL = new URL(ns);
			domAuth = domURL.getAuthority();
		} catch (MalformedURLException e1) {
			logger.debug("Vocabulary Loader. Badly formed URL: {}",ns);
		}
		
		if (domAuth == null) {
			logger.debug("Cannot get authority for {}", ns);
		} else if (!(this.failSafeMap.containsKey(domAuth))){
			try{
				ExecutorService executor = Executors.newSingleThreadExecutor();
				Model m = null;
				
				final Future<Model> handler = executor.submit(new Callable<Model>() {
				    @Override
				    public Model call() {	
					    	logger.debug("Loading {}", ns);
					    	Model m = null;
					    	try {
//					    		m = RDFDataMgr.loadModel(ns);
					    		m = ModelFactory.createDefaultModel();
					    		StreamRDF dest = StreamRDFLib.graph(m.getGraph());
					    		RDFParser parser = RDFParser.source(ns).httpAccept("text/turtle").forceLang(Lang.TURTLE).build();
					    		parser.parse(dest);
					    	} catch (RiotException re) {
					    		logger.warn(ns+" cannot be fetched using text/turtle. Trying to fetch data using application/rdf+xml");
					    		m = ModelFactory.createDefaultModel();
					    		StreamRDF dest = StreamRDFLib.graph(m.getGraph());
					    		RDFParser parser = RDFParser.source(ns).httpAccept("application/rdf+xml").forceLang(Lang.RDFXML).build();
					    		parser.parse(dest);
					    	} catch (Exception e) {
					    		logger.error(ns+" cannot be fetched. Exception: "+e.getMessage());
					    	} 
					    	return m;
				    }
				});
				executor.shutdown();
				
				try {
					m = handler.get(5, TimeUnit.SECONDS);
					dataset.addNamedModel(ns, m);
					
					StringBuilderWriter writer = new StringBuilderWriter();
					m.write(writer, "TURTLE");
					
					CachedVocabulary cv = new CachedVocabulary();
					cv.setLanguage("TURTLE");
					cv.setNs(ns);
					cv.setTextualContent(writer.toString());
					
					dcm.addToCache(LinkedDataMetricsCacheManager.VOCABULARY_CACHE, ns, cv);
					updateFailSafeCache(domAuth); // if we manage to access a vocabulary, then we have to reset the cache counter
				} catch (Exception e)  {
					logger.error("Vocabulary {} could not be accessed. Exception: {}",ns,e.getMessage());
					handler.cancel(true);
					executor.shutdownNow();
					addToFailSafeDecision(domAuth);
				} 
			} catch (Exception e){
				logger.error("Vocabulary {} could not be accessed. Exception: {}",ns,e.getMessage());
				addToFailSafeDecision(domAuth);
	//			throw new VocabularyUnreachableException("The vocabulary <"+ns+"> cannot be accessed. Error thrown: "+e.getMessage());
			}
		} else {
			this.failSafeMap.get(domAuth);
		}
	}
	
	private synchronized void downloadAndLoadVocab(final String ns, final Node term) {
		String domAuth = "";
		try {
			URL domURL = new URL(ns);
			domAuth = domURL.getAuthority();
		} catch (MalformedURLException e1) {
//			e1.printStackTrace();
		}
		
		if (domAuth == null) {
			logger.debug("Cannot get authority for {}", ns);
		} else if (!(this.failSafeMap.containsKey(domAuth))){
			try{
				ExecutorService executor = Executors.newSingleThreadExecutor();
				Model m = null;
				
				final Future<Model> handler = executor.submit(new Callable<Model>() {
				    @Override
				    public Model call() throws Exception {
				    	logger.debug("Loading {}", ns);
				    	Model m = null;
				    	try{ m = RDFDataMgr.loadModel(term.getURI(), Lang.RDFXML); 	} catch (Exception e)
				    	{
				    		try{ m = RDFDataMgr.loadModel(term.getURI(), Lang.TURTLE); } catch (Exception e2)
				    		{
				    			try { m = RDFDataMgr.loadModel(term.getURI(), Lang.NTRIPLES); }
				    			catch (Exception e3){
				    				logger.error("Vocabulary {} could not be accessed after 3 attempts. ",  ns);
					    		}
				    		} 
				    	}
				    	return m;
				    }
				});
				executor.shutdown();
				
				try {
					m = handler.get(5, TimeUnit.SECONDS);
					dataset.addNamedModel(ns, m);
					
					StringBuilderWriter writer = new StringBuilderWriter();
					m.write(writer, "TURTLE");
					
					CachedVocabulary cv = new CachedVocabulary();
					cv.setLanguage("TURTLE");
					cv.setNs(ns);
					cv.setTextualContent(writer.toString());
					
					dcm.addToCache(LinkedDataMetricsCacheManager.VOCABULARY_CACHE, ns, cv);
					updateFailSafeCache(domAuth);
				} catch (Exception e)  {
					logger.error("Vocabulary {} could not be accessed.",ns);
					handler.cancel(true);
					executor.shutdownNow();
					addToFailSafeDecision(domAuth);
				} 
			} catch (Exception e){
				logger.error("Vocabulary {} could not be accessed.",ns);
//				throw new VocabularyUnreachableException("The vocabulary <"+ns+"> cannot be accessed. Error thrown: "+e.getMessage());
				addToFailSafeDecision(domAuth);
			}
		} else {
			this.failSafeMap.get(domAuth);
		}
	}
	
	public void clearDataset(){
		this.dataset.close();
		this.dataset = DatasetFactory.createGeneral();
	}
	
	// --- Vocabulary Helper Methods --- //
	
	public Boolean checkTerm(Node term){
		String ns = term.getNameSpace();
		
		if(!(dataset.containsNamedModel(ns))) loadNStoDataset(ns);
		return termExists(ns, term);
	}
	
    private Boolean termExists(String ns, Node term){
    	if (termsExists.containsKey(term.getURI())){
    		return termsExists.get(term.getURI());
    	} else {
			Model m = (getModelForVocabulary(term).size() > 0) ? getModelForVocabulary(term) : null;
			if (m == null) return false;
			
			m.enterCriticalSection(Lock.READ);
			try{
				if ((term.getNameSpace().startsWith(RDF.getURI())) && (term.getURI().matches(RDF.getURI()+"_[0-9]+"))){
					termsExists.putIfAbsent(term.getURI(),true);
				} else if (term.isURI()) {
					termsExists.putIfAbsent(term.getURI(), m.containsResource(ResourceCommons.asRDFNode(term)));
				}
			} finally {
				m.leaveCriticalSection();
			}
			return (termsExists.get(term.getURI()) == null) ? false : termsExists.get(term.getURI());
    	}
	}

	public Boolean knownVocabulary(String uri){
		return (knownVocabularies.containsKey(uri) || dataset.containsNamedModel(uri));
	}
	
	public Model getModelForVocabulary(String ns){
		if(!(dataset.containsNamedModel(ns))) 
			loadNStoDataset(ns);
		
		return dataset.getNamedModel(ns);
	}
	
	public synchronized Model getModelForVocabulary(Node term){
		String ns = term.getNameSpace();
		if (term.getURI().contains("#")){
			//it is a hash URI then we can use the namespace to download the vocabulary
			ns = term.getNameSpace();
		} else if (this.knownVocabularies.containsKey(term.getNameSpace())) {
			// if not hash URI but we know the vocabulary, e.g the open vocab
			ns = term.getNameSpace();
		}
		
		
		if(!(dataset.containsNamedModel(ns))) 
			if (ns.contains("#")){
				//if it is a hash URI then we just need to download the schema in the namespace
				loadNStoDataset(ns);
			} else {
				loadNStoDataset(ns, term);
			}
		
		return dataset.getNamedModel(ns);
	}
	
	
	public boolean isProperty(Node term, boolean first){
//		String ns = term.getNameSpace();

		if (!isPropertyMap.containsKey(term.getURI())){
			Model m = (getModelForVocabulary(term).size() > 0) ? getModelForVocabulary(term) : null;
			if (m == null) return false;
			
			m.enterCriticalSection(Lock.READ);
			try{
				boolean isProperty = (m.contains(ResourceCommons.asRDFNode(term).asResource(), RDF.type, RDF.Property) ||
						m.contains(ResourceCommons.asRDFNode(term).asResource(), RDF.type, OWL.DatatypeProperty) ||
						m.contains(ResourceCommons.asRDFNode(term).asResource(), RDF.type, OWL.OntologyProperty) ||
						m.contains(ResourceCommons.asRDFNode(term).asResource(), RDF.type, OWL.AnnotationProperty) ||
						m.contains(ResourceCommons.asRDFNode(term).asResource(), RDF.type, OWL.ObjectProperty));
				
				if (!isProperty){
					//try inferring
					try{
						if (first){
							Node inferred = m.listObjectsOfProperty(ResourceCommons.asRDFNode(term).asResource(), RDF.type).next().asNode();
							
							if ((inferred.getURI().equals(OWL.Class.getURI())) || (inferred.getURI().equals(RDFS.Class.getURI())))
								isProperty = false;
							else
								isProperty = isProperty(inferred, false);
						}
					} catch (Exception e){}
				}
				
				isPropertyMap.putIfAbsent(term.getURI(), isProperty);
			} finally {
				m.leaveCriticalSection();
			}
		}
		
		return isPropertyMap.get(term.getURI());
	}
	
	public boolean isProperty(Node term){
		return isProperty(term, true);
	}
	
	public Boolean isObjectProperty(Node term, Boolean first){
//		String ns = term.getNameSpace();
		if (!objectProperties.containsKey(term.getURI())){
			Model m = (getModelForVocabulary(term).size() > 0) ? getModelForVocabulary(term) : null;
			if (m == null) return false;
						
			m.enterCriticalSection(Lock.READ);
			try{
				boolean isProperty = m.contains(ResourceCommons.asRDFNode(term).asResource(),  RDF.type, OWL.ObjectProperty);
				
				if (!isProperty){
					try{
						if (first){
							logger.debug("Trying to infer class");
							Node inferred = m.listObjectsOfProperty(ResourceCommons.asRDFNode(term).asResource(), RDF.type).next().asNode();
							
							if ((inferred.getURI().equals(OWL.Class.getURI())) || (inferred.getURI().equals(RDFS.Class.getURI())))
								isProperty = false;
							else
								isProperty = isObjectProperty(inferred,false);
						}
					} catch (Exception e){
						logger.debug("Error Thrown in the isObjectProperty method: {}. Error: {}",term.toString(), e.getMessage());
					}
				}
				objectProperties.putIfAbsent(term.getURI(), isProperty);
			} finally {
				m.leaveCriticalSection();
			}
		}
		
		return objectProperties.get(term.getURI());
	}
	
	public Boolean isObjectProperty(Node term){
		if (term.getURI().equals(RDF.type.getURI())) return false;
		return isObjectProperty(term,true);
	}
	
	public Boolean isDatatypeProperty(Node term, boolean first){
//		String ns = term.getNameSpace();
		
		if (!datatypeProperties.containsKey(term.getURI())){
			Model m = (getModelForVocabulary(term).size() > 0) ? getModelForVocabulary(term) : null;
			if (m == null) return false;
			
			m.enterCriticalSection(Lock.READ);
			try{
				boolean isProperty = m.contains(ResourceCommons.asRDFNode(term).asResource(),  RDF.type, OWL.DatatypeProperty);
				
				if (!isProperty){
					try{
						if (first){
							logger.debug("Trying to infer class");
							Node inferred = m.listObjectsOfProperty(ResourceCommons.asRDFNode(term).asResource(), RDF.type).next().asNode();
							
							if ((inferred.getURI().equals(OWL.Class.getURI())) || (inferred.getURI().equals(RDFS.Class.getURI())))
								isProperty = false;
							else
								isProperty = isDatatypeProperty(inferred,false);
						}
					} catch (Exception e){
						logger.debug("Error Thrown in the isDatatypeProperty method: {}. Error: {}",term.toString(), e.getMessage());
					}
				}
				
				datatypeProperties.putIfAbsent(term.getURI(), isProperty);
			} finally {
				m.leaveCriticalSection();
			}
		}
		return datatypeProperties.get(term.getURI());
	}
	
	public Boolean isDatatypeProperty(Node term){
		if (term.getURI().equals(RDF.type.getURI())) return false;
		return isDatatypeProperty(term,true);
	}
	
	public Boolean isClass(Node term, boolean first){
//		String ns = term.getNameSpace();
		if (!isClassMap.containsKey(term.getURI())){
			Model m = (getModelForVocabulary(term).size() > 0) ? getModelForVocabulary(term) : null;
			if (m == null) return false;
			
			m.enterCriticalSection(Lock.READ);
			try{
				boolean isClass = (m.contains(ResourceCommons.asRDFNode(term).asResource(), RDF.type,  OWL.Class) || m.contains(ResourceCommons.asRDFNode(term).asResource(), RDF.type,  RDFS.Class));
				
				if (!isClass){
					//try inferring
					try{
						if (first){
							logger.debug("Trying to infer class");
							Node inferred = m.listObjectsOfProperty(ResourceCommons.asRDFNode(term).asResource(), RDF.type).next().asNode();
							
							if ( (inferred.getURI().equals(RDF.Property.getURI())) ||
							(inferred.getURI().equals(OWL.DatatypeProperty.getURI())) ||
							(inferred.getURI().equals(OWL.OntologyProperty.getURI())) ||
							(inferred.getURI().equals(OWL.AnnotationProperty.getURI())) ||
							(inferred.getURI().equals(OWL.ObjectProperty.getURI())) ||
							(inferred.getURI().equals(OWL.FunctionalProperty.getURI()))
									) {
								// if the inferred class is one of the properties, then it is a property
								isClass = false;
							} else {
								isClass = isClass(inferred,true);
							}
						}
					} catch (Exception e){}
				}
				
				isClassMap.putIfAbsent(term.getURI(), isClass);
			} finally {
				m.leaveCriticalSection();
			}
		}
		return isClassMap.get(term.getURI());
	}
	
	public Boolean isClass(Node term){
		return isClass(term,true);
	}
	
	public boolean isDeprecatedTerm(Node term){
		if (checkedDeprecatedTerm.containsKey(term.getURI())) return checkedDeprecatedTerm.get(term.getURI());
		
		Model m = (getModelForVocabulary(term).size() > 0) ? getModelForVocabulary(term) : null;
		if (m == null) return false;
		
		m.enterCriticalSection(Lock.READ);
		try{ 
			Resource r = ResourceCommons.asRDFNode(term).asResource();
			boolean isDeprecated = m.listObjectsOfProperty(r, RDF.type).filterKeep(o -> o.equals(OWL.DeprecatedClass) || o.equals(OWL.DeprecatedProperty)).hasNext();
			checkedDeprecatedTerm.putIfAbsent(term.getURI(), isDeprecated);
		} finally {
			m.leaveCriticalSection();
		}
		return checkedDeprecatedTerm.get(term.getURI());
	}

	public Set<RDFNode> getPropertyDomain(Node term){
		if (propertyDomains.containsKey(term.getURI())) return propertyDomains.get(term.getURI());
		
//		String ns = term.getNameSpace();
		
		Set<RDFNode> set = new HashSet<RDFNode>();

		Model m = (getModelForVocabulary(term).size() > 0) ? getModelForVocabulary(term) : null;
		if (m == null) return set;
		
		m.enterCriticalSection(Lock.READ);
		try{ 
			Set<RDFNode> _tmp = m.listObjectsOfProperty(ResourceCommons.asRDFNode(term).asResource(), RDFS.domain).toSet();
			for (RDFNode node : _tmp){
				if (m.contains(node.asResource(), OWL.unionOf)){
					set.addAll(m.listObjectsOfProperty(node.asResource(), OWL.unionOf).toSet());
				} else {
					set.add(node);
				}
			}
			propertyDomains.putIfAbsent(term.getURI(), set);
		} finally {
			m.leaveCriticalSection();
		}
		
		return propertyDomains.get(term.getURI());
	}
	
	public Set<RDFNode> getPropertyRange(Node term){
		if (propertyRanges.containsKey(term.getURI())) return propertyRanges.get(term.getURI());
//		String ns = term.getNameSpace();
		
		Set<RDFNode> set = new HashSet<RDFNode>();

		Model m = (getModelForVocabulary(term).size() > 0) ? getModelForVocabulary(term) : null;
		if (m == null) return set;
		
		m.enterCriticalSection(Lock.READ);
		try{ 
			Set<RDFNode> _tmp = m.listObjectsOfProperty(ResourceCommons.asRDFNode(term).asResource(), RDFS.range).toSet();
			for (RDFNode node : _tmp){
				if (m.contains(node.asResource(), OWL.unionOf)){
					set.addAll(m.listObjectsOfProperty(node.asResource(), OWL.unionOf).toSet());
				} else {
					set.add(node);
				}
			}
			
			if (set.contains(RDFS.Literal)){
				set.add(XSD.xfloat);
				set.add(XSD.xdouble);
				set.add(XSD.xint);
				set.add(XSD.xlong);
				set.add(XSD.xshort);
				set.add(XSD.xbyte);
				set.add(XSD.xboolean);
				set.add(XSD.xstring);
				set.add(XSD.unsignedByte);
				set.add(XSD.unsignedShort);
				set.add(XSD.unsignedInt);
				set.add(XSD.unsignedLong);
				set.add(XSD.decimal);
				set.add(XSD.integer);
				set.add(XSD.nonPositiveInteger);
				set.add(XSD.nonNegativeInteger);
				set.add(XSD.positiveInteger);
				set.add(XSD.negativeInteger);
				set.add(XSD.normalizedString);
				set.add(XSD.date);
				set.add(XSD.dateTime);
				set.add(XSD.gDay);
				set.add(XSD.gMonth);
				set.add(XSD.gYear);
				set.add(XSD.gMonthDay);
				set.add(XSD.gYearMonth);
				set.add(XSD.hexBinary);
				set.add(XSD.language);
				set.add(XSD.time);
			}
			
			propertyRanges.putIfAbsent(term.getURI(), set);
		} finally {
			m.leaveCriticalSection();
		}

		return propertyRanges.get(term.getURI());
	}
	
	public Set<RDFNode> inferParentClass(Node term){
		if (parentNodes.containsKey(term.getURI())){
			return parentNodes.get(term.getURI());
		} else {
//			String ns = term.getNameSpace();

			Set<RDFNode> set = new LinkedHashSet<RDFNode>();
			
			Model m = (getModelForVocabulary(term).size() > 0) ? getModelForVocabulary(term) : null;
			if (m == null) return set;
			
			m.enterCriticalSection(Lock.READ);
			try{ 
				if (m != null){
					String query = "SELECT ?super { <"+term.getURI()+"> <"+RDFS.subClassOf.getURI()+">* ?super }";
					
					QueryExecution q = QueryExecutionFactory.create(query,m);
					ResultSet rs = q.execSelect();
					while(rs.hasNext()) set.add(rs.next().get("super"));
				}
				
//				set.add(OWL.Thing);
				set.remove(ResourceCommons.asRDFNode(term));
				
				parentNodes.putIfAbsent(term.getURI(), set);
			} finally {
				m.leaveCriticalSection();
			}
			
			return parentNodes.get(term.getURI());
		}
	}
	
	public Set<RDFNode> inferParentProperty(Node term){
		if (parentNodes.containsKey(term.getURI())){
			return parentNodes.get(term.getURI());
		} else {
//			String ns = term.getNameSpace();
			Set<RDFNode> set = new LinkedHashSet<RDFNode>();

	
			Model m = (getModelForVocabulary(term).size() > 0) ?  getModelForVocabulary(term) : null;
			if (m == null) return set;
			
			m.enterCriticalSection(Lock.READ);
			try{ 
				if (m != null){
					String query = "SELECT ?super { <"+term.getURI()+"> <"+RDFS.subPropertyOf.getURI()+">* ?super }";

					QueryExecution q = QueryExecutionFactory.create(query,m);
					ResultSet rs = q.execSelect();
					while(rs.hasNext()) set.add(rs.next().get("super"));
				}
				
				parentNodes.putIfAbsent(term.getURI(), set);
			} finally {
				m.leaveCriticalSection();
			}
			
			return parentNodes.get(term.getURI());
		}
	}
	
	public Set<RDFNode> inferChildClass(Node term){
		if (childNodes.containsKey(term.getURI())){
			return childNodes.get(term.getURI());
		} else {
//			String ns = term.getNameSpace();
			Set<RDFNode> set = new LinkedHashSet<RDFNode>();

			Model m = (getModelForVocabulary(term).size() > 0) ? getModelForVocabulary(term) : null;
			if (m == null) return new LinkedHashSet<RDFNode>();
						
			m.enterCriticalSection(Lock.READ);
			try{ 
				String query = "SELECT ?child { ?child <"+RDFS.subClassOf.getURI()+">* <"+term.getURI()+"> }";
				
				QueryExecution q = QueryExecutionFactory.create(query,m);
				ResultSet rs = q.execSelect();
				while(rs.hasNext()) set.add(rs.next().get("child"));

				childNodes.putIfAbsent(term.getURI(), set);
			} finally {
				m.leaveCriticalSection();
			}
			return childNodes.get(term.getURI());
		}
	}
	
	public Set<RDFNode> inferChildProperty(Node term){
		if (childNodes.containsKey(term.getURI())){
			return childNodes.get(term.getURI());
		} else {
//			String ns = term.getNameSpace();
			Set<RDFNode> set = new LinkedHashSet<RDFNode>();

			Model m = (getModelForVocabulary(term).size() > 0) ?  getModelForVocabulary(term) : null;
			if (m == null) return new LinkedHashSet<RDFNode>();

			m.enterCriticalSection(Lock.READ);
			try{ 
				String query = "SELECT ?child { ?child <"+RDFS.subPropertyOf.getURI()+">* <"+term.getURI()+"> }";
				
				QueryExecution q = QueryExecutionFactory.create(query,m);
				ResultSet rs = q.execSelect();
				while(rs.hasNext()) set.add(rs.next().get("child"));
				
				childNodes.putIfAbsent(term.getURI(), set);
			} finally {
				m.leaveCriticalSection();
			}
			return childNodes.get(term.getURI());
		}
	}
	
	public boolean isInverseFunctionalProperty(Node term){
		
//		String ns = term.getNameSpace();
		
		if (!isIFPMap.containsKey(term.getURI())){
			Model m = (getModelForVocabulary(term).size() > 0) ? getModelForVocabulary(term) : null;
			if (m == null) return false;
			m.enterCriticalSection(Lock.READ);
			try{ 
				isIFPMap.putIfAbsent(term.getURI(), (m.contains(ResourceCommons.asRDFNode(term).asResource(), RDF.type, OWL.InverseFunctionalProperty)));
			} finally {
				m.leaveCriticalSection();
			}
		}
		
		return isIFPMap.get(term.getURI());
	}
	
	public Set<RDFNode> getDisjointWith(Node term){
		if (disjointWith.containsKey(term.getURI())){
			return disjointWith.get(term.getURI());
		} else {
//			String ns = term.getNameSpace();
			Model m = (getModelForVocabulary(term).size() > 0) ? getModelForVocabulary(term) : null;
			if (m == null) return new LinkedHashSet<RDFNode>();
			m.enterCriticalSection(Lock.READ);
			try{ 
				Set<RDFNode> set = new LinkedHashSet<RDFNode>(m.listObjectsOfProperty(ResourceCommons.asRDFNode(term).asResource(), OWL.disjointWith).toSet());
				
				Set<RDFNode> parent = new LinkedHashSet<RDFNode>(inferParentClass(term));
				parent.remove(OWL.Thing);
				for(RDFNode n : parent){
					if (n.isAnon()) continue;
					set.addAll(getDisjointWith(n.asNode()));
				}
				
				disjointWith.putIfAbsent(term.getURI(), set);
			} finally {
				m.leaveCriticalSection();
			}
			return disjointWith.get(term.getURI());
		}
	}
	
	// --- Deprecated Methods --- //
	private Map<Node, Set<RDFNode>> infParent = new HashMap<Node,Set<RDFNode>>();
	@Deprecated
	public Set<RDFNode> inferParent(Node term, Model m, boolean isSuperClass){
		
		if (infParent.containsKey(term)) return infParent.get(term);
		
		String query;
		Model _mdl = m;
		
		if (_mdl == null){
			String ns = term.getNameSpace();

			if(!(dataset.containsNamedModel(ns))) loadNStoDataset(ns);
			_mdl = dataset.getNamedModel(ns);
		}
		
		if (isSuperClass)
			query = "SELECT ?super { <"+term.getURI()+"> <"+RDFS.subClassOf.getURI()+">* ?super }";
		else
			query = "SELECT ?super { <"+term.getURI()+"> <"+RDFS.subPropertyOf.getURI()+">* ?super }";
		
		
		QueryExecution q = QueryExecutionFactory.create(query,_mdl);
		ResultSet rs = q.execSelect();
		Set<RDFNode> set = new LinkedHashSet<RDFNode>();
		while(rs.hasNext()) set.add(rs.next().get("super"));
		set.add(OWL.Thing);
				
		infParent.put(term, set);
		
		return set;
	}
	
	@Deprecated
	public Set<RDFNode> inferChildren(Node term, Model m, boolean isSuperClass){
		String query;
		Model _mdl = m;
		
		if (_mdl == null){
			String ns = term.getNameSpace();

			if(!(dataset.containsNamedModel(ns))) loadNStoDataset(ns);
			_mdl = dataset.getNamedModel(ns);
		}
		
		if (isSuperClass)
			query = "SELECT ?child { ?child <"+RDFS.subClassOf.getURI()+">* <"+term.getURI()+"> }";
		else
			query = "SELECT ?child { ?child <"+RDFS.subPropertyOf.getURI()+">* <"+term.getURI()+"> }";
		
		QueryExecution q = QueryExecutionFactory.create(query,_mdl);
		ResultSet rs = q.execSelect();
		Set<RDFNode> set = new HashSet<RDFNode>();
		while(rs.hasNext()) set.add(rs.next().get("child"));
		return set;
	}

	public Model getClassModelNoLiterals(Node term, Model m){
		String query  = "SELECT * { <"+term.getURI()+"> ?p ?o }";
		Model _mdl = m;
		Model _ret = ModelFactory.createDefaultModel();
		
		if (_mdl == null){
			String ns = term.getNameSpace();

			if(!(dataset.containsNamedModel(ns))) loadNStoDataset(ns);
			_mdl = dataset.getNamedModel(ns);
		}
		 
		
		QueryExecution q = QueryExecutionFactory.create(query,_mdl);
		ResultSet rs = q.execSelect();
		while(rs.hasNext()) {
			QuerySolution qs = rs.next();
			if (qs.get("o").isLiteral()) continue;
			else {
				Resource prop = qs.get("p").asResource();
				Resource obj = qs.getResource("o");
				_ret.add(ResourceCommons.asRDFNode(term).asResource(), _ret.createProperty(prop.getURI()), obj);
			}
			
		}
		
		return _ret;
	}
	
	@Deprecated
	public Model inferAncDec(Node term, Model m){
		Model _mdl = m;
		Model _ret = ModelFactory.createDefaultModel();
		
		if (_mdl == null){
			String ns = term.getNameSpace();

			if(!(dataset.containsNamedModel(ns))) loadNStoDataset(ns);
			_mdl = dataset.getNamedModel(ns);
		}
		
		String query = "SELECT ?super ?type { <"+term.getURI()+"> <"+RDFS.subClassOf.getURI()+"> ?super . ?super a ?type .}";
		QueryExecution q = QueryExecutionFactory.create(query,_mdl);
		ResultSet rs = q.execSelect();
		while(rs.hasNext()) {
			QuerySolution sol = rs.next();
			_ret.add(ResourceCommons.asRDFNode(term).asResource(), RDFS.subClassOf, sol.get("super"));
			_ret.add(sol.get("super").asResource(), RDF.type, sol.get("type"));
		}
		
		query = "SELECT ?child ?type { ?child <"+RDFS.subClassOf.getURI()+"> <"+term.getURI()+">  . ?child a ?type . }";
		q = QueryExecutionFactory.create(query,_mdl);
		rs = q.execSelect();
		while(rs.hasNext()) {
			QuerySolution sol = rs.next();
			_ret.add(sol.get("child").asResource(), RDFS.subClassOf,ResourceCommons.asRDFNode(term).asResource());
			_ret.add(sol.get("child").asResource(), RDF.type, sol.get("type"));

		}
		
		return _ret;
	}
	
	
//public static void main(String [] args) {
////		Node n = ModelFactory.createDefaultModel().createResource("http://dbpedia.org/property/clubB&").asNode();
//////		System.out.println(VocabularyLoader.getInstance().checkTerm(n));
//
//		Node n = ModelFactory.createDefaultModel().createResource("http://iflastandards.info/ns/unimarc/unimarcb/elements/801/U801_3b").asNode();
//		System.out.println(VocabularyLoader.getInstance().checkTerm(n));
////		VocabularyLoader.getInstance();
////		loadNStoDataset(n);
//	}
	
}