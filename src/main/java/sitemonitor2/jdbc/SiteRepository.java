package sitemonitor2.jdbc;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

public interface SiteRepository extends CrudRepository<Site, Long> {
	
	Iterable<Site> findByEnabledTrue();
	
	List<Site> findAllByOrderByNameAsc();
	
}
