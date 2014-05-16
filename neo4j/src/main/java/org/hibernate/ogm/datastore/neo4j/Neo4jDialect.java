/*
 * Hibernate OGM, Domain model persistence for NoSQL datastores
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.ogm.datastore.neo4j;

import static org.hibernate.ogm.datastore.neo4j.dialect.impl.CypherCRUD.relationshipType;
import static org.hibernate.ogm.datastore.neo4j.dialect.impl.NodeLabel.ENTITY;
import static org.hibernate.ogm.datastore.neo4j.dialect.impl.NodeLabel.TEMP_NODE;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.hibernate.AssertionFailure;
import org.hibernate.LockMode;
import org.hibernate.dialect.lock.LockingStrategy;
import org.hibernate.engine.spi.QueryParameters;
import org.hibernate.id.IntegralDataTypeHolder;
import org.hibernate.ogm.datastore.neo4j.dialect.impl.CypherCRUD;
import org.hibernate.ogm.datastore.neo4j.dialect.impl.MapsTupleIterator;
import org.hibernate.ogm.datastore.neo4j.dialect.impl.Neo4jAssociationSnapshot;
import org.hibernate.ogm.datastore.neo4j.dialect.impl.Neo4jSequenceGenerator;
import org.hibernate.ogm.datastore.neo4j.dialect.impl.Neo4jTupleSnapshot;
import org.hibernate.ogm.datastore.neo4j.dialect.impl.Neo4jTypeConverter;
import org.hibernate.ogm.datastore.neo4j.dialect.impl.NodesTupleIterator;
import org.hibernate.ogm.datastore.neo4j.impl.Neo4jDatastoreProvider;
import org.hibernate.ogm.datastore.spi.Association;
import org.hibernate.ogm.datastore.spi.AssociationContext;
import org.hibernate.ogm.datastore.spi.AssociationOperation;
import org.hibernate.ogm.datastore.spi.Tuple;
import org.hibernate.ogm.datastore.spi.TupleContext;
import org.hibernate.ogm.datastore.spi.TupleOperation;
import org.hibernate.ogm.dialect.GridDialect;
import org.hibernate.ogm.dialect.TupleIterator;
import org.hibernate.ogm.grid.AssociationKey;
import org.hibernate.ogm.grid.EntityKey;
import org.hibernate.ogm.grid.EntityKeyMetadata;
import org.hibernate.ogm.grid.RowKey;
import org.hibernate.ogm.loader.nativeloader.BackendCustomQuery;
import org.hibernate.ogm.massindex.batchindexing.Consumer;
import org.hibernate.ogm.query.NoOpParameterMetadataBuilder;
import org.hibernate.ogm.query.spi.ParameterMetadataBuilder;
import org.hibernate.ogm.type.GridType;
import org.hibernate.persister.entity.Lockable;
import org.hibernate.type.Type;
import org.neo4j.cypher.javacompat.ExecutionResult;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterator;

/**
 * Abstracts Hibernate OGM from Neo4j.
 * <p>
 * A {@link Tuple} is saved as a {@link Node} where the columns are converted into properties of the node.<br>
 * An {@link Association} is converted into a {@link Relationship} identified by the {@link AssociationKey} and the
 * {@link RowKey}. The type of the relationship is the value returned by {@link AssociationKey#getCollectionRole()}.
 * <p>
 * If the value of a property is set to null the property will be removed (Neo4j does not allow to store null values).
 *
 * @author Davide D'Alto <davide@hibernate.org>
 */
public class Neo4jDialect implements GridDialect {

	private final CypherCRUD neo4jCRUD;

	private final Neo4jSequenceGenerator neo4jSequenceGenerator;

	public Neo4jDialect(Neo4jDatastoreProvider provider) {
		this.neo4jCRUD = new CypherCRUD( provider.getDataBase() );
		this.neo4jSequenceGenerator = provider.getSequenceGenerator();
	}

	@Override
	public LockingStrategy getLockingStrategy(Lockable lockable, LockMode lockMode) {
		throw new UnsupportedOperationException( "LockMode " + lockMode + " is not supported by the Neo4j GridDialect" );
	}

	@Override
	public Tuple getTuple(EntityKey key, TupleContext context) {
		Node entityNode = neo4jCRUD.findNode( key, ENTITY );
		if ( entityNode == null ) {
			return null;
		}
		return createTuple( entityNode );
	}

	private static Tuple createTuple(Node entityNode) {
		return new Tuple( new Neo4jTupleSnapshot( entityNode ) );
	}

	@Override
	public Tuple createTuple(EntityKey key, TupleContext tupleContext) {
		return createTuple( neo4jCRUD.createNodeUnlessExists( key, ENTITY ) );
	}

	@Override
	public void updateTuple(Tuple tuple, EntityKey key, TupleContext tupleContext) {
		Node node = (Node) ( (Neo4jTupleSnapshot) tuple.getSnapshot() ).getPropertyContainer();
		applyTupleOperations( node, tuple.getOperations() );
	}

	@Override
	public void removeTuple(EntityKey key, TupleContext tupleContext) {
		neo4jCRUD.remove( key );
	}

	@Override
	public Tuple createTupleAssociation(AssociationKey associationKey, RowKey rowKey) {
		PropertyContainer property = createRelationioshipToEntityOrToTempNode( associationKey, rowKey );
		return new Tuple( new Neo4jTupleSnapshot( property ) );
	}

	/**
	 * When dealing with bidirectional associations, OGM calls this method twice, the first time with the information
	 * related to the owner of the association and the {@link RowKey}, the second time using the same {@link RowKey} but
	 * with the {@link AssociationKey} referring to the other side of the association. What happen in this method is
	 * that the first time I'm going to save the {@link RowKey} information in a temporary node and the second time I'm
	 * going to delete the node and connect the two entity with two relationships.
	 * <p>
	 * This approach works at the moment because everything is inside a transaction.
	 */
	private PropertyContainer createRelationioshipToEntityOrToTempNode(AssociationKey associationKey, RowKey rowKey) {
		Node rowKeyNode = neo4jCRUD.findNode( rowKey );
		if ( rowKeyNode == null ) {
			EntityKey endNodeKey = endNodeKey( associationKey, rowKey );
			Node endNode = neo4jCRUD.findNode( endNodeKey, ENTITY );
			if ( endNode == null ) {
				// We cannot find the entity on the other side of the relationship, we store the information related to
				// the RowKey in a temporary node and we create a relationship to it
				return createRelationshipToTempNode( associationKey, rowKey );
			}
			else if ( associationKey.getCollectionRole().equals( rowKey.getTable() ) ) {
				// Unidirectional ManyToOne: the node contains the field with the association
				// I'm not creating a relationship at the moment for this case
				return endNode;
			}
			else {
				// Bidirectional ManyToOne: the node contains the field with the association.
				// I'll create the relationship between the owner and the end node
				return createRelationshipWithEntity( associationKey, rowKey, endNode );
			}
		}
		else if ( rowKeyNode.hasLabel( ENTITY ) ) {
			// The Rowkey represents an entity and we are going to create the relationship with it
			return createRelationshipWithEntity( associationKey, rowKey, rowKeyNode );
		}
		else if ( rowKeyNode.hasLabel( TEMP_NODE ) ) {
			// We have found a temporary node related to this association, we are going to delete it and connect the
			// entity pointing to the temporary node and the owner of this association.
			return deleteTempNodeAndUpdateRelationshipWithEntity( associationKey, rowKey, rowKeyNode );
		}
		else {
			throw new AssertionFailure( "Unrecognized RowKeyode: " + rowKeyNode );
		}
	}

	/**
	 * This method returns the {@link EntityKey} that represents the entity on the other side of the relationship.
	 * <p>
	 * At the moment the {@link AssociationKey} contains the owner of the association but it is missing the information
	 * related to the entity on the other side of the association. What we do to obtain it is remove from {@link RowKey}
	 * the columns in the {@link AssociationKey}, the remaining one should represents the identifier at the end fo the
	 * association.
	 */
	private EntityKey endNodeKey(AssociationKey associationKey, RowKey rowKey) {
		List<String> keyColumnNames = new ArrayList<String>();
		List<Object> keyColumnValues = new ArrayList<Object>();
		String[] columnNames = rowKey.getColumnNames();
		int i = 0;
		for ( String columnName : columnNames ) {
			boolean entityColumn = true;
			for ( String associationColumnName : associationKey.getColumnNames() ) {
				if ( associationColumnName.equals( columnName ) ) {
					entityColumn = false;
					break;
				}
			}
			if ( entityColumn ) {
				keyColumnNames.add( columnName );
				keyColumnValues.add( rowKey.getColumnValues()[i] );
			}
			i++;
		}
		return new EntityKey( new EntityKeyMetadata( associationKey.getTable(), keyColumnNames.toArray( new String[keyColumnNames.size()] ) ),
				keyColumnValues.toArray( new Object[keyColumnValues.size()] ) );
	}

	private Relationship deleteTempNodeAndUpdateRelationshipWithEntity(AssociationKey associationKey, RowKey rowKey, Node rowKeyNode) {
		Node ownerNode = neo4jCRUD.findNode( associationKey.getEntityKey(), ENTITY );
		Relationship inverseRelationship = updateInverseRelationship( rowKey, rowKeyNode, ownerNode );

		RelationshipType associationType = relationshipType( associationKey );
		Relationship relationship = null;
		if ( !associationKey.getCollectionRole().equals( associationKey.getTable() ) ) {
			relationship = ownerNode.createRelationshipTo( inverseRelationship.getStartNode(), associationType );
			applyColumnValues( rowKey, relationship );
		}
		return relationship;
	}

	private Relationship updateInverseRelationship(RowKey rowKey, Node rowKeyNode, Node ownerNode) {
		Relationship inverseRelationship = rowKeyNode.getRelationships( Direction.INCOMING ).iterator().next();
		Relationship newInverseRelationship = inverseRelationship.getStartNode().createRelationshipTo( ownerNode, inverseRelationship.getType() );
		applyColumnValues( rowKey, newInverseRelationship );
		inverseRelationship.delete();
		inverseRelationship.getEndNode().delete();
		return newInverseRelationship;
	}

	private PropertyContainer createRelationshipWithEntity(AssociationKey associationKey, RowKey rowKey, Node node) {
		EntityKey ownerEntityKey = associationKey.getEntityKey();
		Node ownerNode = neo4jCRUD.findNode( ownerEntityKey, ENTITY );
		Relationship relationship = ownerNode.createRelationshipTo( node, relationshipType( associationKey ) );
		applyColumnValues( rowKey, relationship );
		return relationship;
	}

	private PropertyContainer createRelationshipToTempNode(AssociationKey associationKey, RowKey rowKey) {
		Node rowKeyNode = neo4jCRUD.createNodeUnlessExists( rowKey, TEMP_NODE );
		return createRelationshipWithEntity( associationKey, rowKey, rowKeyNode );
	}

	private void applyColumnValues(RowKey rowKey, PropertyContainer relationship) {
		for ( int i = 0; i < rowKey.getColumnNames().length; i++ ) {
			// Neo4j does not support null values but in the embedded case it might happen to have some nulls
			if ( rowKey.getColumnValues()[i] != null ) {
				relationship.setProperty( rowKey.getColumnNames()[i], rowKey.getColumnValues()[i] );
			}
		}
	}

	@Override
	public Association getAssociation(AssociationKey associationKey, AssociationContext associationContext) {
		Node entityNode = neo4jCRUD.findNode( associationKey.getEntityKey(), ENTITY );
		if ( entityNode == null ) {
			return null;
		}
		return new Association( new Neo4jAssociationSnapshot( entityNode, associationKey ) );
	}

	@Override
	public Association createAssociation(AssociationKey associationKey, AssociationContext associationContext) {
		return new Association();
	}

	@Override
	public void updateAssociation(Association association, AssociationKey key, AssociationContext associationContext) {
		for ( AssociationOperation action : association.getOperations() ) {
			applyAssociationOperation( key, action, associationContext );
		}
	}

	@Override
	public boolean isStoredInEntityStructure(AssociationKey associationKey, AssociationContext associationContext) {
		return false;
	}

	@Override
	public void nextValue(RowKey key, IntegralDataTypeHolder value, int increment, int initialValue) {
		int nextValue = neo4jSequenceGenerator.nextValue( key, increment, initialValue );
		value.initialize( nextValue );
	}

	@Override
	public GridType overrideType(Type type) {
		return Neo4jTypeConverter.INSTANCE.convert( type );
	}

	@Override
	public void removeAssociation(AssociationKey key, AssociationContext associationContext) {
		if ( key != null ) {
			neo4jCRUD.remove( key );
		}
	}

	private void applyAssociationOperation(AssociationKey key, AssociationOperation operation, AssociationContext associationContext) {
		switch ( operation.getType() ) {
		case CLEAR:
			removeAssociation( key, associationContext );
			break;
		case PUT:
			putAssociationOperation( key, operation );
			break;
		case PUT_NULL:
			removeAssociationOperation( key, operation );
			break;
		case REMOVE:
			removeAssociationOperation( key, operation );
			break;
		}
	}

	private void putAssociationOperation(AssociationKey associationKey, AssociationOperation action) {
		Relationship relationship = neo4jCRUD.findRelationship( associationKey, action.getKey() );
		applyTupleOperations( relationship, action.getValue().getOperations() );
	}

	private void removeAssociationOperation(AssociationKey associationKey, AssociationOperation action) {
		neo4jCRUD.remove( associationKey, action.getKey() );
	}

	private void applyTupleOperations(PropertyContainer node, Set<TupleOperation> operations) {
		for ( TupleOperation operation : operations ) {
			applyOperation( node, operation );
		}
	}

	private void applyOperation(PropertyContainer node, TupleOperation operation) {
		switch ( operation.getType() ) {
		case PUT:
			putTupleOperation( node, operation );
			break;
		case PUT_NULL:
			removeTupleOperation( node, operation );
			break;
		case REMOVE:
			removeTupleOperation( node, operation );
			break;
		}
	}

	private void removeTupleOperation(PropertyContainer node, TupleOperation operation) {
		if ( node.hasProperty( operation.getColumn() ) ) {
			node.removeProperty( operation.getColumn() );
		}
	}

	private void putTupleOperation(PropertyContainer node, TupleOperation operation) {
		node.setProperty( operation.getColumn(), operation.getValue() );
	}

	@Override
	public void forEachTuple(Consumer consumer, EntityKeyMetadata... entityKeyMetadatas) {
		for ( EntityKeyMetadata entityKeyMetadata : entityKeyMetadatas ) {
			ResourceIterator<Node> queryNodes = neo4jCRUD.findNodes( entityKeyMetadata.getTable() );
			try {
				while ( queryNodes.hasNext() ) {
					Node next = queryNodes.next();
					Tuple tuple = createTuple( next );
					consumer.consume( tuple );
				}
			}
			finally {
				queryNodes.close();
			}
		}
	}

	@Override
	public TupleIterator executeBackendQuery(BackendCustomQuery customQuery, QueryParameters queryParameters, EntityKeyMetadata[] metadatas) {
		ExecutionResult result = neo4jCRUD.executeQuery( customQuery.getSQL() );
		if ( metadatas.length == 1 ) {
			return new NodesTupleIterator( result );
		}
		return new MapsTupleIterator( result );
	}

	@Override
	public ParameterMetadataBuilder getParameterMetadataBuilder() {
		return NoOpParameterMetadataBuilder.INSTANCE;
	}
}
