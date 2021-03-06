/**
 * 
 */
package io.github.luzzu.linkeddata.qualitymetrics.accessibility.availability;

import java.util.ArrayList;
import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.impl.StatementImpl;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.luzzu.exceptions.MetricProcessingException;
import io.github.luzzu.linkeddata.qualitymetrics.accessibility.availability.helper.Dereferencer;
import io.github.luzzu.linkeddata.qualitymetrics.accessibility.availability.helper.ModelParser;
import io.github.luzzu.linkeddata.qualitymetrics.accessibility.availability.helper.Tld;
import io.github.luzzu.linkeddata.qualitymetrics.commons.AbstractQualityMetric;
import io.github.luzzu.linkeddata.qualitymetrics.commons.HTTPResourceUtils;
import io.github.luzzu.linkeddata.qualitymetrics.commons.HTTPRetriever;
import io.github.luzzu.linkeddata.qualitymetrics.commons.cache.LinkedDataMetricsCacheManager;
import io.github.luzzu.linkeddata.qualitymetrics.vocabulary.DQM;
import io.github.luzzu.linkeddata.qualitymetrics.vocabulary.DQMPROB;
import io.github.luzzu.operations.properties.EnvironmentProperties;
import io.github.luzzu.qualitymetrics.algorithms.ReservoirSampler;
import io.github.luzzu.qualitymetrics.commons.cache.CachedHTTPResource;
import io.github.luzzu.qualitymetrics.commons.datatypes.HTTPDereference.StatusCode;
import io.github.luzzu.qualitymetrics.commons.serialisation.SerialisableHttpResponse;
import io.github.luzzu.qualityproblems.ProblemCollection;
import io.github.luzzu.qualityproblems.ProblemCollectionModel;
import io.github.luzzu.semantics.commons.ResourceCommons;
import io.github.luzzu.semantics.vocabularies.DAQ;
import io.github.luzzu.semantics.vocabularies.QPRO;

/**
 * @author Jeremy Debattista
 * 
 * In "Misreported Content Type" metric we check if RDF/XML content is
 * returned with a reported type other than application/rdf+xml
 * 
 * In the estimated version of this metric, we make use of reservoir 
 * sampling in order to sample a set of TDL and FQU (fully qualified 
 * uri), similar to the approach taken for EstimatedDereferenceability
 * 
 */
public class EstimatedMisreportedContentType extends AbstractQualityMetric<Double> {
	private final Resource METRIC_URI = DQM.MisreportedContentTypesMetric;

	private double misReportedType=0;
	private double correctReportedType=0;
	private double notOkResponses=0;
	

	private HTTPRetriever httpRetreiver = new HTTPRetriever();
	private boolean metricCalculated = false;

	private long totalNumberOfTriples = 0;
	private long totalNumberOfResources = 0;


	private static Logger logger = LoggerFactory.getLogger(EstimatedMisreportedContentType.class);
	boolean followRedirects = true;
	
	
	private ProblemCollection<Model> problemCollection = new ProblemCollectionModel(DQM.MisreportedContentTypesMetric);
	private boolean requireProblemReport = EnvironmentProperties.getInstance().requiresQualityProblemReport();

	
	/**
	 * Constants controlling the maximum number of elements in the reservoir of Top-level Domains and 
	 * Fully Qualified URIs of each TLD, respectively
	 */
	private static int MAX_TLDS = 50;
	private static int MAX_FQURIS_PER_TLD = 100000;
	private ReservoirSampler<Tld> tldsReservoir = new ReservoirSampler<Tld>(MAX_TLDS, true);
	private List<String> uriSet = new ArrayList<String>();


	public void compute(Quad quad) throws MetricProcessingException {
		logger.debug("Computing : {} ", quad.asTriple().toString());
		totalNumberOfTriples++;
		
		String subject = quad.getSubject().toString();
		if (httpRetreiver.isPossibleURL(subject)){
			addURIToReservoir(subject);
			totalNumberOfResources++;
		}
		
		String object = quad.getObject().toString();
		if (httpRetreiver.isPossibleURL(object)){
			this.addURIToReservoir(object);
			totalNumberOfResources++;
		}
	}

	public Resource getMetricURI() {
		return this.METRIC_URI;
	}

	@Override
	public Double metricValue() {
		if (!this.metricCalculated){
			for(Tld tld : this.tldsReservoir.getItems()){
				uriSet.addAll(tld.getfqUris().getItems()); 
			}
			
			httpRetreiver.addListOfResourceToQueue(uriSet);
			
			httpRetreiver.start(true);

			this.checkForMisreportedContentType();
			this.metricCalculated = true;
			httpRetreiver.stop();
		}
		
		double metricValue = 0.0;
		logger.debug(String.format("Computing metric. Correct: %.0f. Misreported: %.0f. Not OK: %.0f", correctReportedType, misReportedType, notOkResponses));
		
		
		if((misReportedType + correctReportedType) != 0.0) {
			metricValue = correctReportedType / (misReportedType + correctReportedType);
		}

		return metricValue;
	}

	private void addURIToReservoir(String uri) {
		// Extract the top-level domain (a.k.a pay level domain) and look for it in the reservoir 
		String uriTLD = HTTPRetriever.extractTopLevelDomainURI(uri);
		Tld newTld = new Tld(uriTLD, MAX_FQURIS_PER_TLD);		
		Tld foundTld = this.tldsReservoir.findItem(newTld);
		
		if(foundTld == null) {
			logger.trace("New TLD found and recorded: {}...", uriTLD);
			// Add the new TLD to the reservoir
			// Add new fully qualified URI to those of the new TLD
			
			this.tldsReservoir.add(newTld); 
			newTld.addFqUri(uri);
		} else {
			// The identified TLD was found, it already exists on the reservoir, just add the fqdn to it
			foundTld.addFqUri(uri);
		}
	}
	
	
	private void checkForMisreportedContentType(){
		while(uriSet.size() > 0){
			// Get the next URI to be processed and remove it from the set (i.e. the uriSet is used as a queue, the next element is poped)
			String uri = uriSet.remove(0);	
			
			// Check if the URI has already been dereferenced, in which case, it would be part of the Cache
			CachedHTTPResource httpResource = (CachedHTTPResource) LinkedDataMetricsCacheManager.getInstance().getFromCache(LinkedDataMetricsCacheManager.HTTP_RESOURCE_CACHE, uri);
			
			if (httpResource == null || (httpResource.getResponses() == null && httpResource.getDereferencabilityStatusCode() != StatusCode.BAD)) {
				// If the URI is not part of the cache, add it back in to the uriSet, so that it's tried to be dereferenced by the HTTP Retriever
				if(!uriSet.contains(uri)) {
					uriSet.add(uri);
				}
				continue;
			}
			
			if (Dereferencer.hasOKStatus(httpResource)){
				logger.info("Checking "+httpResource.getUri()+ " for misreported content type");
				
				SerialisableHttpResponse res = HTTPResourceUtils.getSemanticResponse(httpResource);
				if (res != null){
					String ct = res.getHeaders("Content-Type");
					Lang lang = RDFLanguages.contentTypeToLang(ct);
					
					//should the resource be dereferencable?
					if (lang != null){
						//the resource might be a semantic resource
						if (ModelParser.hasRDFContent(httpResource, lang)){
							correctReportedType++;
						} else {
							misReportedType++;
							
							String actualCT = HTTPResourceUtils.determineActualContentType(httpResource) ;
							this.createProblemModel(httpResource.getUri(), ct, actualCT);
						}
					}
				} else {
					logger.info("No semantic content type for {}. Trying to parse the content.", httpResource.getUri());
					SerialisableHttpResponse possible = HTTPResourceUtils.getPossibleSemanticResponse(httpResource); //we are doing this to get more statistical detail for the problem report
					if (possible != null){
						String location = HTTPResourceUtils.getResourceLocation(possible);
						if (location != null){
							Lang language = RDFLanguages.filenameToLang(location); // if the attachment has an non semantic file type, it is skipped
							if (language != null){
								misReportedType++;
								
								String actualCT = HTTPResourceUtils.determineActualContentType(httpResource);
								this.createProblemModel(httpResource.getUri(), possible.getHeaders("Content-Type"), actualCT);
							} else 
								logger.info("Not possible to parse {}. Not a recognised file extension", location);	
						}
					}
					else logger.info("Not possible to parse {}.", httpResource.getUri());
				}
			}
		}
			
	}
	
	
	
	private void createProblemModel(String resource, String expectedContentType, String actualContentType){
		if (this.requireProblemReport) {
			Model m = ModelFactory.createDefaultModel();
			
			Resource subject = m.createResource(resource);
			m.add(new StatementImpl(subject, QPRO.exceptionDescription, DQMPROB.MisreportedTypeException));
			if ((expectedContentType == null) || (expectedContentType.equals("")))
				m.add(new StatementImpl(subject, DQMPROB.expectedContentType, m.createLiteral("Unknown Expected Content Type")));
			else m.add(new StatementImpl(subject, DQMPROB.expectedContentType, m.createLiteral(expectedContentType)));
			if ((actualContentType == null) || (actualContentType.equals("")))
				m.add(new StatementImpl(subject, DQMPROB.actualContentType, m.createLiteral("Unknown Content Type")));
			else 
				m.add(new StatementImpl(subject, DQMPROB.actualContentType, m.createLiteral(actualContentType)));
			this.problemCollection.addProblem(m);
		}
	}
	
	@Override
	public boolean isEstimate() {
		return true;
	}

	@Override
	public Resource getAgentURI() {
		return 	DQM.LuzzuProvenanceAgent;
	}


	@Override
	public ProblemCollection<?> getProblemCollection() {
		return this.problemCollection;
	}

	@Override
	public Model getObservationActivity() {
		Model activity = ModelFactory.createDefaultModel();
		
		Resource mp = ResourceCommons.generateURI();
		activity.add(mp, RDF.type, DAQ.MetricProfile);
		
		
//		correctReportedType, misReportedType, notOkResponses
		activity.add(mp, DAQ.totalDatasetTriplesAssessed, ResourceCommons.generateTypeLiteral((long)this.totalNumberOfTriples));
		activity.add(mp, DQM.totalNumberOfResourcesAssessed, ResourceCommons.generateTypeLiteral((misReportedType + correctReportedType)));
		activity.add(mp, DQM.totalValidContentType, ResourceCommons.generateTypeLiteral((int)correctReportedType));
		activity.add(mp, DQM.totalNumberOfResources, ResourceCommons.generateTypeLiteral(this.totalNumberOfResources));
		activity.add(mp, DAQ.estimationTechniqueUsed, ModelFactory.createDefaultModel().createResource("https://dblp.uni-trier.de/rec/conf/esws/DebattistaL0A15"));


		Resource ep = ResourceCommons.generateURI();
		activity.add(mp, DAQ.estimationParameter, ep);
		activity.add(ep, RDF.type, DAQ.EstimationParameter);
		activity.add(ep, DAQ.estimationParameterValue, ResourceCommons.generateTypeLiteral(MAX_FQURIS_PER_TLD));
		activity.add(ep, DAQ.estimationParameterKey, ResourceCommons.generateTypeLiteral("k"));
		activity.add(ep, RDFS.comment, activity.createLiteral("The size of the reservior for each pay-level domain (pld).", "en"));

		Resource ep2 = ResourceCommons.generateURI();
		activity.add(mp, DAQ.estimationParameter, ep2);
		activity.add(ep2, RDF.type, DAQ.EstimationParameter);
		activity.add(ep2, DAQ.estimationParameterValue, ResourceCommons.generateTypeLiteral(MAX_TLDS));
		activity.add(ep2, DAQ.estimationParameterKey, ResourceCommons.generateTypeLiteral("plds-k"));
		activity.add(ep2, RDFS.comment, activity.createLiteral("The size of the global reservior holding the pay-level domains (pld).", "en"));

		return activity;
	}
}
