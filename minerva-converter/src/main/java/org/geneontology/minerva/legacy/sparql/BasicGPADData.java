package org.geneontology.minerva.legacy.sparql;

import org.apache.jena.graph.Node;
import org.semanticweb.owlapi.model.IRI;

public class BasicGPADData {

	private final Node objectNode;
	private final IRI object;
	private final IRI qualifier;
	private final Node ontologyClassNode;
	private final IRI ontologyClass;
	
	public BasicGPADData(Node objectNode, IRI object, IRI qualifier, Node ontologyClassNode, IRI ontologyClass) {
		this.object = object;
		this.qualifier = qualifier;
		this.ontologyClass = ontologyClass;
		this.objectNode = objectNode;
		this.ontologyClassNode = ontologyClassNode;
	}

	public IRI getObject() {
		return this.object;
	}

	public IRI getQualifier() {
		return this.qualifier;
	}

	public IRI getOntologyClass() {
		return this.ontologyClass;
	}

	public Node getObjectNode() {
		return this.objectNode;
	}

	public Node getOntologyClassNode() {
		return this.ontologyClassNode;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) { return true; }
		else if (!(other instanceof BasicGPADData)) { return false; }
		else {
			BasicGPADData otherData = (BasicGPADData)other;
			return this.getObject().equals(otherData.getObject())
					&& this.getQualifier().equals(otherData.getQualifier())
					&& this.getOntologyClass().equals(otherData.getOntologyClass())
					&& this.getObjectNode().equals(otherData.getObjectNode())
					&& this.getOntologyClassNode().equals(otherData.getOntologyClassNode());
		}
	}

	@Override
	public int hashCode() {
		int result = 17;
		result = 37 * result + this.getObject().hashCode();
		result = 37 * result + this.getQualifier().hashCode();
		result = 37 * result + this.getOntologyClass().hashCode();
		result = 37 * result + this.getObjectNode().hashCode();
		result = 37 * result + this.getOntologyClassNode().hashCode();
		return result;
	}
	
	@Override
	public String toString() {
		return this.object.toString() + this.qualifier.toString() + this.ontologyClass.toString();
	}

}