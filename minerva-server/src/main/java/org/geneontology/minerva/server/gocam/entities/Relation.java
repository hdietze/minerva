package org.geneontology.minerva.server.gocam.entities;

import java.util.HashSet;
import java.util.Set;

import org.geneontology.minerva.json.JsonAnnotation;
import org.geneontology.minerva.json.JsonOwlFact;
import org.geneontology.minerva.json.JsonOwlIndividual;
import org.geneontology.minerva.server.gocam.GOCamTools;
import org.geneontology.minerva.util.AnnotationShorthand;


public class TermAssociation extends Entity{
	private Set<Evidence> evidences;
	private Set<Contributor> contributors;
	private Set<Group> groups;
	private String date;
	
	public TermAssociation(String id, String label) {
		super(null, id, label, EntityType.RELATION);
		evidences = new HashSet<Evidence>();
	}

	public TermAssociation(JsonOwlFact fact) {
		this( fact.property, fact.propertyLabel);
	}
	
	
	public boolean addContributor(Contributor contributor) {
		return contributors.add(contributor);
	}
	
	public boolean addEvidence(Evidence evidence) {
		return this.evidences.add(evidence);
	}	
	
	private void addEvidence(JsonOwlIndividual[] individuals, String uuid) {
		Evidence evidence = GOCamTools.getEvidenceFromId(individuals, uuid);
		addEvidence(evidence);
	}

	public boolean addGroup(Group group) {
		return groups.add(group);
	}
	
	public void addAnnotations(JsonOwlIndividual[] individuals, JsonAnnotation[] annotations) {
		for (JsonAnnotation annotation : annotations) {
			
			if (AnnotationShorthand.evidence.name().equals(annotation.key)) {
				addEvidence(individuals, annotation.value);
			}
			
			if (AnnotationShorthand.contributor.name().equals(annotation.key)) {
				addContributor(new Contributor(annotation.value));
			}
			
			if (AnnotationShorthand.providedBy.name().equals(annotation.key)) {
				addGroup(new Group(annotation.value));
			}

			if (AnnotationShorthand.modelstate.name().equals(annotation.key)) {
				setDate(annotation.value);
			}			
		}
	}
	
	//Getters and Setters
	

	public Set<Evidence> getEvidences() {
		return evidences;
	}

	public void setEvidences(Set<Evidence> evidences) {
		this.evidences = evidences;
	}

	public Set<Contributor> getContributors() {
		return contributors;
	}

	public void setContributors(Set<Contributor> contributors) {
		this.contributors = contributors;
	}

	public Set<Group> getGroups() {
		return groups;
	}

	public void setGroups(Set<Group> groups) {
		this.groups = groups;
	}

	public String getDate() {
		return date;
	}

	public void setDate(String date) {
		this.date = date;
	}	
	
}