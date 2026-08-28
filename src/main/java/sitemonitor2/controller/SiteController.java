package sitemonitor2.controller;

import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;
import sitemonitor2.jdbc.Site;
import sitemonitor2.jdbc.SiteRepository;
import tools.jackson.databind.ObjectMapper;


@Slf4j
@Controller
@RequestMapping("/sites")
public class SiteController {

    private final SiteRepository repository;
    private final ObjectMapper objectMapper;

    public SiteController(SiteRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("sites", repository.findAllByOrderByNameAsc());
        return "sites/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
    	Site site = new Site();
    	site.setFailureLimit(3);
        model.addAttribute("site", site);
        return "sites/form";
    }

    @PostMapping
    public String save(@ModelAttribute Site site) {
        repository.save(site);
        return "redirect:/sites";
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable("id") Long id, Model model) {

        Site site = repository.findById(id)
                .orElseThrow();

        model.addAttribute("site", site);

        return "sites/form";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable("id") Long id) {
        repository.deleteById(id);
        return "redirect:/sites";
    }
    
	@GetMapping("/export")
	public ResponseEntity<byte[]> exportSites() throws Exception {
		
		List<Site> sites = StreamSupport.stream(repository.findAll().spliterator(), false).toList();

		byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sites);

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=sitemonitor-sites.json")
				.contentType(MediaType.APPLICATION_JSON).body(json);
	}
    
	@PostMapping("/import")
	public String importSites(@RequestParam("file") MultipartFile file) throws Exception {

		List<Site> importedSites = Arrays.asList(objectMapper.readValue(file.getInputStream(), Site[].class));

		for (Site site : importedSites) {
			site.setId(null);
			repository.save(site);
		}

		return "redirect:/sites";
	}  
}
