/**
 * 
 */
package org.geneontology.minerva;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.log4j.Logger;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.query.BindingSet;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.TupleQuery;
import org.openrdf.query.TupleQueryResult;
import org.openrdf.repository.RepositoryException;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.semanticweb.owlapi.model.OWLNamedObject;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

import com.bigdata.journal.Options;
import com.bigdata.rdf.sail.BigdataSail;
import com.bigdata.rdf.sail.BigdataSailRepository;
import com.bigdata.rdf.sail.BigdataSailRepositoryConnection;

/**
 * @author benjamingood
 *
 */
public class BlazegraphOntologyManager {
	private static Logger LOG = Logger.getLogger(BlazegraphOntologyManager.class);
	private final BigdataSailRepository go_lego_repo;
	//TODO replace with more stable real URL that gets updated when it exists..  
	private final static String public_blazegraph_url = "http://skyhook.berkeleybop.org/issue-35-neo-test/products/blazegraph/blazegraph-go-lego.jnl.gz";
	//
	private static final Set<String> root_types;
	static {
		root_types =  new HashSet<String>();
		root_types.add("http://purl.obolibrary.org/obo/GO_0008150"); //BP
		root_types.add("http://purl.obolibrary.org/obo/GO_0003674"); //MF
		root_types.add("http://purl.obolibrary.org/obo/GO_0005575"); //CC
		root_types.add("http://purl.obolibrary.org/obo/GO_0032991"); //Complex
		root_types.add("http://purl.obolibrary.org/obo/CHEBI_36080"); //protein
		root_types.add("http://purl.obolibrary.org/obo/CHEBI_33695"); //information biomacromolecule
		root_types.add("http://purl.obolibrary.org/obo/CHEBI_50906");  //chemical role
		root_types.add("http://purl.obolibrary.org/obo/CHEBI_24431"); //chemical entity
		root_types.add("http://purl.obolibrary.org/obo/UBERON_0001062"); //anatomical entity
		root_types.add("http://purl.obolibrary.org/obo/CARO_0000000"); // root root anatomical entity
		root_types.add("http://purl.obolibrary.org/obo/ECO_0000000"); //evidence root.  
	}
	 
	public BigdataSailRepository getGo_lego_repo() {
		return go_lego_repo;
	}
	public BlazegraphOntologyManager(String go_lego_repo_file) throws IOException {
		if(new File(go_lego_repo_file).exists()) {			
			go_lego_repo = initializeRepository(go_lego_repo_file);
		}else {
			LOG.info("No blazegraph tbox journal found at "+go_lego_repo_file+" . Downloading from "+public_blazegraph_url+" and putting there.");
			URL blazegraph_url = new URL(public_blazegraph_url);
			File go_lego_repo_local = new File(go_lego_repo_file);
			if(public_blazegraph_url.endsWith(".gz")) {
				go_lego_repo_local = new File(go_lego_repo_file+".gz");
			}
			org.apache.commons.io.FileUtils.copyURLToFile(blazegraph_url, go_lego_repo_local);
			if(public_blazegraph_url.endsWith(".gz")) {
				unGunzipFile(go_lego_repo_file+".gz", go_lego_repo_file);
			}
			go_lego_repo = initializeRepository(go_lego_repo_file);
		}
	}

	public void unGunzipFile(String compressedFile, String decompressedFile) {		 
        byte[] buffer = new byte[1024]; 
        try { 
            FileInputStream fileIn = new FileInputStream(compressedFile);
            GZIPInputStream gZIPInputStream = new GZIPInputStream(fileIn); 
            FileOutputStream fileOutputStream = new FileOutputStream(decompressedFile); 
            int bytes_read; 
            while ((bytes_read = gZIPInputStream.read(buffer)) > 0) { 
                fileOutputStream.write(buffer, 0, bytes_read);
            }
            gZIPInputStream.close();
            fileOutputStream.close(); 
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
	
	
	private BigdataSailRepository initializeRepository(String pathToJournal) {
		try {
			Properties properties = new Properties();
			properties.load(this.getClass().getResourceAsStream("onto-blazegraph.properties"));
			properties.setProperty(Options.FILE, pathToJournal);
			BigdataSail sail = new BigdataSail(properties);
			BigdataSailRepository repository = new BigdataSailRepository(sail);

			repository.initialize();
			return repository;
		} catch (RepositoryException e) {
			LOG.fatal("Could not create Blazegraph sail", e);
			return null;
		} catch (IOException e) {
			LOG.fatal("Could not create Blazegraph sail", e);
			return null;
		}
	}

	public void loadRepositoryFromOWLFile(File file, String iri, boolean reset) throws OWLOntologyCreationException, RepositoryException, IOException, RDFParseException, RDFHandlerException {
		synchronized(go_lego_repo) {
			final BigdataSailRepositoryConnection connection = go_lego_repo.getUnisolatedConnection();
			try {
				connection.begin();
				try {
					URI graph = new URIImpl(iri);
					if(reset) {
						connection.clear(graph);
					}
					if(file.getName().endsWith(".ttl")) {
						connection.add(file, "", RDFFormat.TURTLE, graph);
					}else if(file.getName().endsWith(".owl")) {
						connection.add(file, "", RDFFormat.RDFXML, graph);
					}
					connection.commit();
				} catch (Exception e) {
					connection.rollback();
					throw e;
				}
			} finally {
				connection.close();
			}
			return ;
		}		
	}

	public Set<String> getAllSuperClasses(String uri) throws IOException {
		Set<String> supers = new HashSet<String>();
		try {
			BigdataSailRepositoryConnection connection = go_lego_repo.getReadOnlyConnection();
			try {
				String query = "PREFIX owl: <http://www.w3.org/2002/07/owl#> " +
						"PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
						+ "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> " +
						"SELECT ?super " +
						"WHERE { " +
						"<"+uri+"> rdfs:subClassOf* ?super . " +
						"} ";
				TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
				TupleQueryResult result = tupleQuery.evaluate();
				while (result.hasNext()) {
					BindingSet binding = result.next();
					Value v = binding.getValue("super");
					//ignore anonymous super classes
					if ( v instanceof URI ) {
						String superclass = binding.getValue("super").stringValue();
						supers.add(superclass);		
					}				
				}
			} catch (MalformedQueryException e) {
				throw new IOException(e);
			} catch (QueryEvaluationException e) {
				throw new IOException(e);
			} finally {
				connection.close();
			}
		} catch (RepositoryException e) {
			throw new IOException(e);
		}
		return supers;
	}
	public Set<String> getAllSubClasses(String uri) throws IOException {
		Set<String> supers = new HashSet<String>();
		try {
			BigdataSailRepositoryConnection connection = go_lego_repo.getReadOnlyConnection();
			try {
				String query = "PREFIX owl: <http://www.w3.org/2002/07/owl#> " +
						"PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
						+ "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> " +
						"SELECT ?sub " +
						"WHERE { " +
						"?sub rdfs:subClassOf* <"+uri+"> . " +
						"} ";
				TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
				TupleQueryResult result = tupleQuery.evaluate();
				while (result.hasNext()) {
					BindingSet binding = result.next();
					Value v = binding.getValue("sub");
					//ignore anonymous sub classes
					if ( v instanceof URI ) {
						String superclass = binding.getValue("sub").stringValue();
						supers.add(superclass);		
					}				
				}
			} catch (MalformedQueryException e) {
				throw new IOException(e);
			} catch (QueryEvaluationException e) {
				throw new IOException(e);
			} finally {
				connection.close();
			}
		} catch (RepositoryException e) {
			throw new IOException(e);
		}
		return supers;
	}
	
	public Map<String, Set<String>> getSuperCategoryMap(Set<String> uris) throws IOException {
		Map<String, Set<String>> sub_supers = new HashMap<String, Set<String>>();
		try {
			BigdataSailRepositoryConnection connection = go_lego_repo.getReadOnlyConnection();
			try {
				String q = "VALUES ?sub {";
				for(String uri : uris) {
					q+="<"+uri+"> ";
				}
				q+="} . " ;
				
				String categories = "VALUES ?super {";
				for(String c : root_types) {
					categories += "<"+c+"> ";
				}
				categories +="} . ";
				String query = "PREFIX owl: <http://www.w3.org/2002/07/owl#> " +
						"PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
						+ "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> " +
						"SELECT ?sub ?super " +
						"WHERE { " + q + categories 
						+ "?sub rdfs:subClassOf* ?super . " +
						"} ";
				TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
				TupleQueryResult result = tupleQuery.evaluate();
				while (result.hasNext()) {
					BindingSet binding = result.next();
					Value parent = binding.getValue("super");
					Value child = binding.getValue("sub");
					//System.out.println(child +" "+parent);
					//ignore anonymous super classes
					if ( parent instanceof URI && child instanceof URI) {
						String superclass = binding.getValue("super").stringValue();
						String subclass = binding.getValue("sub").stringValue();
						Set<String> supers = sub_supers.get(subclass);
						if(supers==null) {
							supers = new HashSet<String>();
						}
						supers.add(superclass);		
						sub_supers.put(subclass, supers);
					}				
				}
			} catch (MalformedQueryException e) {
				throw new IOException(e);
			} catch (QueryEvaluationException e) {
				throw new IOException(e);
			} finally {
				connection.close();
			}
		} catch (RepositoryException e) {
			throw new IOException(e);
		}
		return sub_supers;
	}

/**
 * This reproduces the results of the golr lookup service for gene product typing
 * @param uris
 * @return
 * @throws IOException
 */
	public Map<String, Set<String>> getNeoRoots(Set<String> uris) throws IOException {
		Map<String, Set<String>> all = getSuperClassMap(uris);
		Map<String, Set<String>> roots = new HashMap<String, Set<String>>();
		//only do what the golr was doing and working
		for(String term : all.keySet()) {
			Set<String> isa_closure = all.get(term);
			String direct_parent_iri = null;
			if(isa_closure.contains("http://purl.obolibrary.org/obo/CHEBI_36080")||isa_closure.contains("http://purl.obolibrary.org/obo/PR_000000001")) {
				//protein
				//direct_parent_iri = "http://purl.obolibrary.org/obo/CHEBI_36080";
				direct_parent_iri = "http://purl.obolibrary.org/obo/PR_000000001";
			}else if(isa_closure.contains("http://purl.obolibrary.org/obo/CHEBI_33695")) {
				//information biomacrolecule (gene, complex)
				direct_parent_iri = "http://purl.obolibrary.org/obo/CHEBI_33695";
			}
			if(direct_parent_iri!=null) {
				Set<String> r = new HashSet<String>();
				r.add(direct_parent_iri);
				roots.put(term, r);
			}
		}
		return roots;
	}
	
	
	public Map<String, Set<String>> getSuperClassMap(Set<String> uris) throws IOException {
		Map<String, Set<String>> sub_supers = new HashMap<String, Set<String>>();
		try {
			BigdataSailRepositoryConnection connection = go_lego_repo.getReadOnlyConnection();
			try {
				String q = "VALUES ?sub {";
				for(String uri : uris) {
					q+="<"+uri+"> ";
				}
				q+="} . " ;
				String query = "PREFIX owl: <http://www.w3.org/2002/07/owl#> " +
						"PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
						+ "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> " +
						"SELECT ?sub ?super " +
						"WHERE { " + q 
						+ "?sub rdfs:subClassOf* ?super . " +
						"} ";
				TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
				TupleQueryResult result = tupleQuery.evaluate();
				while (result.hasNext()) {
					BindingSet binding = result.next();
					Value parent = binding.getValue("super");
					Value child = binding.getValue("sub");
					//System.out.println(child +" "+parent);
					//ignore anonymous super classes
					if ( parent instanceof URI && child instanceof URI) {
						String superclass = binding.getValue("super").stringValue();
						String subclass = binding.getValue("sub").stringValue();
						Set<String> supers = sub_supers.get(subclass);
						if(supers==null) {
							supers = new HashSet<String>();
						}
						supers.add(superclass);		
						sub_supers.put(subclass, supers);
					}				
				}
			} catch (MalformedQueryException e) {
				throw new IOException(e);
			} catch (QueryEvaluationException e) {
				throw new IOException(e);
			} finally {
				connection.close();
			}
		} catch (RepositoryException e) {
			throw new IOException(e);
		}
		return sub_supers;
	}
	
	
	public Set<String> getGenesByTaxid(String ncbi_tax_id) throws IOException {
		Set<String> genes = new HashSet<String>();
		try {
			BigdataSailRepositoryConnection connection = go_lego_repo.getReadOnlyConnection();
			try {
				String query =
						"select ?gene   \n" + 
						"where { \n" + 
						"  ?gene rdfs:subClassOf ?taxon_restriction .\n" + 
						"  ?taxon_restriction owl:onProperty <http://purl.obolibrary.org/obo/RO_0002162> .\n" + 
						"  ?taxon_restriction owl:someValuesFrom <http://purl.obolibrary.org/obo/NCBITaxon_"+ncbi_tax_id+"> \n" + 
						"}";

				TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
				TupleQueryResult result = tupleQuery.evaluate();
				while (result.hasNext()) {
					BindingSet binding = result.next();
					Value v = binding.getValue("gene");
					//ignore anonymous sub classes
					if ( v instanceof URI ) {
						String gene = binding.getValue("gene").stringValue();
						genes.add(gene);		
					}				
				}
			} catch (MalformedQueryException e) {
				throw new IOException(e);
			} catch (QueryEvaluationException e) {
				throw new IOException(e);
			} finally {
				connection.close();
			}
		} catch (RepositoryException e) {
			throw new IOException(e);
		}
		return genes;
	}
	
	public Set<String> getAllTaxaWithGenes() throws IOException {
		Set<String> taxa = new HashSet<String>();
		try {
			BigdataSailRepositoryConnection connection = go_lego_repo.getReadOnlyConnection();
			try {
				String query =
						"select distinct ?taxon  \n" + 
								"where { \n" + 
								"  ?gene rdfs:subClassOf ?taxon_restriction .\n" + 
								"  ?taxon_restriction owl:onProperty <http://purl.obolibrary.org/obo/RO_0002162> .\n" + 
								"  ?taxon_restriction owl:someValuesFrom ?taxon \n" + 
								"\n" + 
								"}";

				TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
				TupleQueryResult result = tupleQuery.evaluate();
				while (result.hasNext()) {
					BindingSet binding = result.next();
					Value v = binding.getValue("taxon");
					//ignore anonymous sub classes
					if ( v instanceof URI ) {
						String taxon = binding.getValue("taxon").stringValue();
						taxa.add(taxon);		
					}				
				}
			} catch (MalformedQueryException e) {
				throw new IOException(e);
			} catch (QueryEvaluationException e) {
				throw new IOException(e);
			} finally {
				connection.close();
			}
		} catch (RepositoryException e) {
			throw new IOException(e);
		}
		return taxa;
	}
	
	
	
	public void dispose() {
		try {
			go_lego_repo.shutDown();
		} catch (RepositoryException e) {
			LOG.error("Failed to shutdown Lego Blazegraph sail.", e);
		}
	}
	public Set<String> getTaxaByGenes(Set<String> genes) throws IOException {
		String expansion = "VALUES ?gene { "; 
		for(String gene : genes) {
			expansion += "<"+gene+"> \n";
		}
		expansion+= " } . \n";	
		Set<String> taxa = new HashSet<String>();
		try {
			BigdataSailRepositoryConnection connection = go_lego_repo.getReadOnlyConnection();
			try {
				String query =
						"select distinct ?taxon  \n" + 
								"where { \n" + expansion + 
								"  ?gene rdfs:subClassOf ?taxon_restriction .\n" + 
								"  ?taxon_restriction owl:onProperty <http://purl.obolibrary.org/obo/RO_0002162> .\n" + 
								"  ?taxon_restriction owl:someValuesFrom ?taxon \n" + 
								"\n" + 
								"}";

				TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
				TupleQueryResult result = tupleQuery.evaluate();
				while (result.hasNext()) {
					BindingSet binding = result.next();
					Value v = binding.getValue("taxon");
					//ignore anonymous sub classes
					if ( v instanceof URI ) {
						String taxon = binding.getValue("taxon").stringValue();
						taxa.add(taxon);		
					}				
				}
			} catch (MalformedQueryException e) {
				throw new IOException(e);
			} catch (QueryEvaluationException e) {
				throw new IOException(e);
			} finally {
				connection.close();
			}
		} catch (RepositoryException e) {
			throw new IOException(e);
		}
		return taxa;
	}
	
	
	public String getLabel(OWLNamedObject i) throws IOException {
		String entity = i.getIRI().toString();
		return getLabel(entity);
	}
	
	public String getLabel(String entity) throws IOException {
		String label = null;
		
		String query = "select ?label where { <"+entity+"> rdfs:label ?label } limit 1";		
		try {
			BigdataSailRepositoryConnection connection = go_lego_repo.getReadOnlyConnection();
			try {
				TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
				TupleQueryResult result = tupleQuery.evaluate();
				if (result.hasNext()) {
					BindingSet binding = result.next();
					Value v = binding.getValue("label");
					label = v.stringValue();			
				}
			} catch (MalformedQueryException e) {
				throw new IOException(e);
			} catch (QueryEvaluationException e) {
				throw new IOException(e);
			} finally {
				connection.close();
			}
		} catch (RepositoryException e) {
			throw new IOException(e);
		}
		return label;
	}
}
