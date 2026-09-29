package com.atelier.catalog.service;

import com.atelier.catalog.api.dto.ProductAdminDtos.ImageUpdateRequest;
import com.atelier.catalog.domain.ProductImage;
import com.atelier.catalog.repository.ColorRepository;
import com.atelier.catalog.repository.ProductImageRepository;
import com.atelier.catalog.repository.ProductRepository;
import com.atelier.media.ImageService;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Galeria do produto. A primeira imagem enviada vira a principal (usada no card da listagem). */
@Service
public class ProductImageService {

    static final int MAX_IMAGES = 12;

    private final ProductImageRepository images;
    private final ProductRepository products;
    private final ColorRepository colors;
    private final ImageService imageService;

    ProductImageService(ProductImageRepository images, ProductRepository products, ColorRepository colors,
                        ImageService imageService) {
        this.images = images;
        this.products = products;
        this.colors = colors;
        this.imageService = imageService;
    }

    @Transactional(readOnly = true)
    public List<ProductImage> list(Long productId) {
        return images.findByProductIdOrderByIsMainDescPositionAscIdAsc(productId);
    }

    @Transactional
    public ProductImage upload(Long productId, byte[] data, String alt, Long colorId) {
        if (!products.existsById(productId)) throw new BusinessException(ErrorCode.NOT_FOUND);
        long count = images.countByProductId(productId);
        if (count >= MAX_IMAGES) throw new BusinessException(ErrorCode.INVALID_IMAGE, "Máximo de " + MAX_IMAGES + " imagens por produto");
        checkColor(colorId);

        var stored = imageService.store("products/" + productId, data);
        // Se a transação falhar depois do upload, o arquivo não fica órfão no bucket.
        afterRollback(() -> imageService.delete(stored.key()));

        var image = new ProductImage();
        image.productId = productId;
        image.colorId = colorId;
        image.storageKey = stored.key();
        image.url = stored.url();
        image.width = stored.width();
        image.height = stored.height();
        image.altText = alt.trim();
        image.position = (int) count;
        image.isMain = count == 0;
        return images.save(image);
    }

    @Transactional
    public ProductImage update(Long productId, Long imageId, ImageUpdateRequest req) {
        ProductImage image = get(productId, imageId);
        checkColor(req.colorId());
        image.altText = req.alt().trim();
        image.colorId = req.colorId();
        if (Boolean.TRUE.equals(req.main()) && !image.isMain) {
            images.clearMain(productId);
            image = get(productId, imageId);
            image.isMain = true;
        }
        return images.saveAndFlush(image);
    }

    /** Recebe todos os ids da galeria na nova ordem. */
    @Transactional
    public void reorder(Long productId, List<Long> imageIds) {
        List<ProductImage> current = list(productId);
        if (current.size() != imageIds.size() || !current.stream().map(i -> i.id).toList().containsAll(imageIds)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Informe todos os ids das imagens do produto");
        }
        for (ProductImage image : current) image.position = imageIds.indexOf(image.id);
    }

    @Transactional
    public void delete(Long productId, Long imageId) {
        ProductImage image = get(productId, imageId);
        boolean wasMain = image.isMain;
        images.delete(image);
        images.flush();
        if (wasMain) {
            list(productId).stream().findFirst().ifPresent(next -> next.isMain = true);
        }
        String key = image.storageKey;
        afterCommit(() -> imageService.delete(key));
    }

    private ProductImage get(Long productId, Long imageId) {
        return images.findByIdAndProductId(imageId, productId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private void checkColor(Long colorId) {
        if (colorId != null && !colors.existsById(colorId)) throw new BusinessException(ErrorCode.NOT_FOUND, "Cor inexistente");
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static void afterRollback(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) action.run();
            }
        });
    }
}
