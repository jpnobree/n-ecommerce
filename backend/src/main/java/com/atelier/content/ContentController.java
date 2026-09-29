package com.atelier.content;

import com.atelier.content.ContentDtos.*;
import com.atelier.media.ImageService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ContentController {

    /** Banners de campanha podem ter formato vertical (mobile): exigência de largura menor que a de produto. */
    private static final int BANNER_MIN_WIDTH = 720;

    private final ContentService content;
    private final ImageService images;

    ContentController(ContentService content, ImageService images) {
        this.content = content;
        this.images = images;
    }

    @GetMapping("/home")
    ResponseEntity<Home> home() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()).body(content.home());
    }

    @PostMapping("/newsletter")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void subscribe(@Valid @RequestBody NewsletterRequest req) {
        content.subscribe(req.email());
    }

    /** Dados para o sitemap.xml (gerado pelo servidor SSR). */
    @GetMapping("/seo/sitemap")
    ResponseEntity<List<SitemapEntry>> sitemap() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(content.sitemap());
    }

    // ---- admin ----

    @GetMapping("/admin/banners")
    List<BannerResponse> banners() {
        return content.allBanners().stream().map(BannerResponse::of).toList();
    }

    @PostMapping("/admin/banners")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    BannerResponse createBanner(@Valid @RequestBody BannerRequest req) {
        return BannerResponse.of(content.saveBanner(null, req));
    }

    @PutMapping("/admin/banners/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    BannerResponse updateBanner(@PathVariable Long id, @Valid @RequestBody BannerRequest req) {
        return BannerResponse.of(content.saveBanner(id, req));
    }

    @DeleteMapping("/admin/banners/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void deleteBanner(@PathVariable Long id) {
        content.deleteBanner(id);
    }

    /** Upload de imagem avulsa (banners). Devolve a URL a usar no banner. */
    @PostMapping(value = "/admin/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        var stored = images.store("banners", file.getBytes(), BANNER_MIN_WIDTH);
        return new UploadResponse(stored.url(), stored.width(), stored.height());
    }
}
