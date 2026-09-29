package com.atelier.catalog.service;

import com.atelier.catalog.api.dto.CategoryDtos.*;
import com.atelier.catalog.domain.Category;
import com.atelier.catalog.repository.CategoryRepository;
import com.atelier.catalog.repository.ProductRepository;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Árvore de categorias com caminho de slugs materializado ("feminino/vestidos"). */
@Service
public class CategoryService {

    private final CategoryRepository categories;
    private final ProductRepository products;

    CategoryService(CategoryRepository categories, ProductRepository products) {
        this.categories = categories;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<CategoryNode> publicTree() {
        return tree(categories.findByActiveTrueOrderByDepthAscSortOrderAscNameAsc());
    }

    @Transactional(readOnly = true)
    public List<Category> all() {
        return categories.findAllByOrderByDepthAscSortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public CategoryPage page(String path) {
        Category c = categories.findBySlugPathAndActiveTrue(path)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        List<Crumb> breadcrumb = new ArrayList<>();
        String[] parts = path.split("/");
        for (int i = 1; i <= parts.length; i++) {
            String p = String.join("/", Arrays.copyOf(parts, i));
            categories.findBySlugPathAndActiveTrue(p).ifPresent(a -> breadcrumb.add(new Crumb(a.name, a.slugPath)));
        }
        List<Crumb> children = categories.findByActiveTrueOrderByDepthAscSortOrderAscNameAsc().stream()
                .filter(x -> c.id.equals(x.parentId))
                .map(x -> new Crumb(x.name, x.slugPath))
                .toList();
        return new CategoryPage(CategoryResponse.of(c), breadcrumb, children);
    }

    @Transactional
    public Category create(CategoryRequest req) {
        var c = new Category();
        Category parent = req.parentId() == null ? null : get(req.parentId());
        if (parent != null && parent.depth >= Category.MAX_DEPTH) throw new BusinessException(ErrorCode.CATEGORY_DEPTH_EXCEEDED);
        c.parentId = req.parentId();
        c.depth = (short) (parent == null ? 0 : parent.depth + 1);
        c.slug = req.slug() != null ? req.slug() : Slugs.of(req.name());
        c.slugPath = pathOf(parent, c.slug);
        if (categories.existsBySlugPath(c.slugPath)) throw new BusinessException(ErrorCode.SLUG_TAKEN);
        apply(c, req);
        return categories.save(c);
    }

    /** Renomear o slug ou mover de pai reescreve o caminho de toda a subárvore. */
    @Transactional
    public Category update(Long id, CategoryRequest req) {
        Category c = get(id);
        Category parent = req.parentId() == null ? null : get(req.parentId());
        if (parent != null && (parent.id.equals(c.id) || parent.slugPath.startsWith(c.slugPath + "/"))) {
            throw new BusinessException(ErrorCode.CATEGORY_CYCLE);
        }
        int newDepth = parent == null ? 0 : parent.depth + 1;
        if (newDepth + categories.subtreeHeight(c.slugPath, c.depth) > Category.MAX_DEPTH) {
            throw new BusinessException(ErrorCode.CATEGORY_DEPTH_EXCEEDED);
        }
        String slug = req.slug() != null ? req.slug() : c.slug;
        String newPath = pathOf(parent, slug);
        String oldPath = c.slugPath;
        int depthDelta = newDepth - c.depth;
        if (!newPath.equals(oldPath) && categories.existsBySlugPath(newPath)) throw new BusinessException(ErrorCode.SLUG_TAKEN);

        c.parentId = req.parentId();
        c.slug = slug;
        c.slugPath = newPath;
        c.depth = (short) newDepth;
        apply(c, req);
        categories.saveAndFlush(c);
        if (!newPath.equals(oldPath) || depthDelta != 0) {
            categories.rewriteDescendants(oldPath, newPath, depthDelta);
        }
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        Category c = get(id);
        if (categories.existsByParentId(id) || products.existsByMainCategoryId(id)) {
            throw new BusinessException(ErrorCode.CATEGORY_IN_USE);
        }
        categories.delete(c);
    }

    public Category get(Long id) {
        return categories.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static String pathOf(Category parent, String slug) {
        return parent == null ? slug : parent.slugPath + "/" + slug;
    }

    private static void apply(Category c, CategoryRequest req) {
        c.name = req.name().trim();
        c.description = req.description();
        if (req.sortOrder() != null) c.sortOrder = req.sortOrder();
        if (req.active() != null) c.active = req.active();
        if (req.featured() != null) c.featured = req.featured();
        c.metaTitle = req.metaTitle();
        c.metaDescription = req.metaDescription();
    }

    /** Lista já ordenada por profundidade → árvore. Filhas de categorias inativas somem junto. */
    private static List<CategoryNode> tree(List<Category> sorted) {
        Map<Long, List<CategoryNode>> children = new HashMap<>();
        Map<Long, CategoryNode> nodes = new LinkedHashMap<>();
        List<CategoryNode> roots = new ArrayList<>();
        for (Category c : sorted) {
            var kids = new ArrayList<CategoryNode>();
            children.put(c.id, kids);
            var node = new CategoryNode(c.id, c.name, c.slug, c.slugPath, c.featured, kids);
            nodes.put(c.id, node);
            if (c.parentId == null) roots.add(node);
            else if (children.containsKey(c.parentId)) children.get(c.parentId).add(node);
        }
        return roots;
    }
}
