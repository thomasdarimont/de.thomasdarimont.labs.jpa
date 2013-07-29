/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.thomasdarimont.labs.jpa.eclipselink;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.Persistence;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.ParameterExpression;
import javax.persistence.criteria.Root;
import javax.persistence.spi.PersistenceProvider;
import javax.persistence.spi.PersistenceProviderResolver;
import javax.persistence.spi.PersistenceProviderResolverHolder;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import de.thomasdarimont.labs.jpa.domain.users.User;

/**
 * Standalone JPA test-suite for EclipseLink persistence provider tests / bug reports.
 * 
 * @author Thomas Darimont
 */
public class EclipseLinkPlainJpaTests {

	private static final String ECLIPSELINK_PERSISTENCEXML = "eclipselink.persistencexml";
	private static final String PERSISTENCE_UNIT_NAME = "default";

	private static EntityManagerFactory emf;
	private EntityManager em;
	private EntityTransaction tx;

	@BeforeClass
	public static void setupClass() {
		System.setProperty(ECLIPSELINK_PERSISTENCEXML, "META-INF/eclipselink-plainjpa-persistence.xml");
		PersistenceProviderResolverHolder.setPersistenceProviderResolver(new EclipseLinkOnlyPersistenceProviderResolve());
		emf = Persistence.createEntityManagerFactory(PERSISTENCE_UNIT_NAME);
	}

	@Before
	public void setup() {
		em = emf.createEntityManager();
		tx = em.getTransaction();
		tx.begin();
	}

	@After
	public void teardown() {
		try {
			tx.rollback();
		} catch (Exception e) {
			System.err.println("Could not rollback EntityTransaction!");
			e.printStackTrace();
		}
		try {
			em.close();
		} catch (Exception e) {
			System.err.println("Could not close EntityManager!");
			e.printStackTrace();
		}
	}

	public static void afterClass() {
		System.getProperties().remove(ECLIPSELINK_PERSISTENCEXML);
		try {
			emf.close();
		} catch (Exception e) {
			System.err.println("Could not close EntityManagerFactory!");
			e.printStackTrace();
		}
	}

	@Test
	public void dummy() {}

	/**
	 * Produces:
	 * 
	 * <pre>
	 * Local Exception Stack: 
	 * Exception [EclipseLink-4002] (Eclipse Persistence Services - 2.4.0.v20120608-r11652): org.eclipse.persistence.exceptions.DatabaseException
	 * Internal Exception: java.sql.SQLSyntaxErrorException: unexpected token: )
	 * Error Code: -5581
	 * Call: SELECT ID, ACTIVE, AGE, CREATEDAT, EMAILADDRESS, FIRSTNAME, LASTNAME, MANAGER_ID FROM USER WHERE (FIRSTNAME IN ((?,?)))
	 * 	bind => [2 parameters bound]
	 * Query: ReadAllQuery(referenceClass=User sql="SELECT ID, ACTIVE, AGE, CREATEDAT, EMAILADDRESS, FIRSTNAME, LASTNAME, MANAGER_ID FROM USER WHERE (FIRSTNAME IN (?))")
	 * ...
	 * Caused by: org.hsqldb.HsqlException: unexpected token: )
	 * 	at org.hsqldb.error.Error.parseError(Unknown Source)
	 * </pre>
	 */
	@Ignore
	@Test
	public void eclipselink349477() {

		User user = new User("Dave", "Matthews", "foo@bar.de");
		em.persist(user);
		em.flush();

		CriteriaBuilder builder = em.getCriteriaBuilder();

		CriteriaQuery<User> criteria = builder.createQuery(User.class);
		Root<User> root = criteria.from(User.class);
		criteria.where(root.get("firstname").in(builder.parameter(Collection.class)));

		TypedQuery<User> query = em.createQuery(criteria);
		for (ParameterExpression parameter : criteria.getParameters()) {
			query.setParameter(parameter, Arrays.asList("Dave", "Carter"));
		}

		List<User> result = query.getResultList();
		assertFalse(result.isEmpty());
	}

	/**
	 * @see https://bugs.eclipse.org/bugs/show_bug.cgi?id=413892
	 */
	@Test
	public void eclipselink413892ListAllUsersSortedByTheirManagersFirstname() {

		User firstUser = new User("Oliver", "Gierke", "gierke@synyx.de");
		firstUser.setAge(28);
		User secondUser = new User("Joachim", "Arrasz", "arrasz@synyx.de");
		secondUser.setAge(35);
		User thirdUser = new User("Dave", "Matthews", "no@email.com");
		thirdUser.setAge(43);
		User fourthUser = new User("Thomas", "Darimont", "tdarimont@gopivotal.com");
		fourthUser.setAge(31);

		firstUser.setManager(null);
		em.persist(firstUser);
		secondUser.setManager(null);
		em.persist(secondUser);
		thirdUser.setManager(firstUser); // manager Oliver
		em.persist(thirdUser);
		fourthUser.setManager(secondUser); // manager Joachim
		em.persist(fourthUser);

		em.flush();

		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<User> criteria = cb.createQuery(User.class);
		Root<User> root = criteria.from(User.class);

		/*
		 * Un-commenting the following line triggers the generation of an "old-style" SQL inner join:
		 * -> from User u1, User u2 where u1.manager = u2.id
		 * ... instead of the expected left outer join. 
		 */
		// root.get("manager"); // this generates an old-style sql-join regard-less of what comes next

		Join<User, User> join = root.join("manager", JoinType.LEFT);
		criteria.orderBy(cb.asc(join.get("firstname")));
		TypedQuery<User> query = em.createQuery(criteria);

		List<User> result = query.getResultList();
		assertFalse(result.isEmpty());

		/*
		 * Because of the generated inner join the following asserts fail:
		 */
		assertThat(result.get(0).getManager(), is(nullValue()));
		assertThat(result.get(1).getManager(), is(nullValue()));
		assertThat(result.get(2).getManager().getFirstname(), is("Joachim"));
		assertThat(result.get(3).getManager().getFirstname(), is("Oliver"));
	}

	static class EclipseLinkOnlyPersistenceProviderResolve implements PersistenceProviderResolver {
		@Override
		public List<PersistenceProvider> getPersistenceProviders() {
			return Arrays.<PersistenceProvider> asList(new org.eclipse.persistence.jpa.PersistenceProvider());
		}

		@Override
		public void clearCachedProviders() {}
	}
}
