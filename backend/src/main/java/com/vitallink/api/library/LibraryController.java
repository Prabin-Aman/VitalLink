package com.vitallink.api.library;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.library.LibraryService.Article;
import com.vitallink.api.library.LibraryService.ArticleDetail;
import com.vitallink.api.library.LibraryService.Category;
import com.vitallink.api.library.LibraryService.Library;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shared content for signed-in users. Reads aren't recorded per user. */
@RestController
@RequestMapping("/v1/library")
@Tag(name = "library")
public class LibraryController {

    private final LibraryService service;

    public LibraryController(LibraryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Categories, the featured articles (web and mobile) and the trending list.")
    public Library library(CurrentUser me) {
        return service.library();
    }

    @GetMapping("/categories")
    public List<Category> categories(CurrentUser me) {
        return service.categories();
    }

    @GetMapping("/categories/{code}")
    public List<Article> category(CurrentUser me, @PathVariable String code) {
        return service.category(code);
    }

    @GetMapping("/articles/{slug}")
    @Operation(summary = "One article with its Markdown body.")
    public ArticleDetail article(CurrentUser me, @PathVariable String slug) {
        return service.article(slug);
    }
}
