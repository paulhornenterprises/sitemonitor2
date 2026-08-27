package sitemonitor2.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import lombok.extern.slf4j.Slf4j;
import sitemonitor2.jdbc.Site;
import sitemonitor2.jdbc.SiteRepository;

@Slf4j
@Controller
@RequestMapping("/sites")
public class SiteController {

    private final SiteRepository repository;

    public SiteController(SiteRepository repository) {
        this.repository = repository;
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
}
