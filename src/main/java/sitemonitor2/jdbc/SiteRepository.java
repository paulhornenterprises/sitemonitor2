package sitemonitor2.jdbc;

import org.springframework.data.repository.CrudRepository;

public interface SiteRepository extends CrudRepository<Site, Long> {
	
	Iterable<Site> findByEnabledTrue();
	
}
