package com.bruno.kota.services;
import java.math.BigDecimal;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.bruno.kota.dtos.AddProductsToGroupResult;
import com.bruno.kota.dtos.PagedResponse;
import com.bruno.kota.dtos.ProductRequest;
import com.bruno.kota.dtos.ProductResponse;
import com.bruno.kota.entities.Bid;
import com.bruno.kota.entities.Product;
import com.bruno.kota.entities.ProductGroup;
import com.bruno.kota.entities.QuotationItem;
import com.bruno.kota.exceptions.DuplicateResourceException;
import com.bruno.kota.exceptions.InactiveResourceException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.ProductGroupRepository;
import com.bruno.kota.repositories.ProductRepository;
import com.bruno.kota.repositories.QuotationItemRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final ProductGroupRepository productGroupRepository;

    private static final Collator PT_BR_COLLATOR = Collator.getInstance(new Locale("pt", "BR"));

    @Transactional(readOnly = true)
    public List<ProductResponse> findAll() {
        return productRepository.findAll().stream()
                .map(this::toResponseWithoutPricing)
                .sorted((a, b) -> PT_BR_COLLATOR.compare(a.name(), b.name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<ProductResponse> search(String term, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name"));
        Page<Product> resultPage = (term == null || term.isBlank())
                ? productRepository.findAll(pageable)
                : productRepository.findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(term, term, pageable);

        List<Product> products = resultPage.getContent();
        Map<Long, QuotationItem> lastWonByProduct = loadLastWonBatch(products);

        List<ProductResponse> content = products.stream()
                .map(p -> toResponse(p, lastWonByProduct.get(p.getId())))
                .toList();

        return new PagedResponse<>(content, resultPage.getNumber(), resultPage.getSize(),
                resultPage.getTotalElements(), resultPage.getTotalPages());
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> findAllInactive() {
        List<Product> inactive = productRepository.findAllInactive();
        Map<Long, QuotationItem> lastWonByProduct = loadLastWonBatch(inactive);
        return inactive.stream()
                .map(p -> toResponse(p, lastWonByProduct.get(p.getId())))
                .sorted((a, b) -> PT_BR_COLLATOR.compare(a.name(), b.name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        Product product = findEntityById(id);
        List<QuotationItem> won = quotationItemRepository.findLastWonByProductId(id);
        return toResponse(product, won.isEmpty() ? null : won.get(0));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product existing = productRepository.findByBarcodeIncludingDeleted(request.barcode()).orElse(null);

        if (existing != null) {
            if (Boolean.TRUE.equals(existing.getDeleted())) {
                throw new InactiveResourceException(
                        "Já existe um produto inativo com este código de barras (id " + existing.getId() + "). Reative-o em vez de criar um novo.",
                        existing.getId()
                );
            }
            throw new DuplicateResourceException("Já existe um produto com o código de barras " + request.barcode());
        }

        Product product = Product.builder()
                .barcode(request.barcode())
                .name(request.name())
                .description(request.description())
                .unitOfMeasure(request.unitOfMeasure())
                .build();

        return toResponseWithoutPricing(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = findEntityById(id);
        product.setBarcode(request.barcode());
        product.setName(request.name());
        product.setDescription(request.description());
        product.setUnitOfMeasure(request.unitOfMeasure());
        return toResponseWithoutPricing(productRepository.save(product));
    }

    @Transactional
    public ProductResponse reactivate(Long id, ProductRequest request) {
        Product product = productRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado: id " + id));
        product.setDeleted(false);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setUnitOfMeasure(request.unitOfMeasure());
        return toResponseWithoutPricing(productRepository.save(product));
    }

    @Transactional
    public void delete(Long id) {
        productRepository.delete(findEntityById(id));
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> findByGroupId(Long groupId) {
        ProductGroup group = productGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado: id " + groupId));
        return productRepository.findByGroup(group).stream()
                .map(this::toResponseWithoutPricing)
                .toList();
    }

    @Transactional
    public ProductResponse addToGroup(Long productId, Long groupId) {
        Product product = findEntityById(productId);
        ProductGroup group = productGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado: id " + groupId));
        product.getGroups().add(group);
        return toResponseWithoutPricing(productRepository.save(product));
    }

    // Uma transação só pra lista inteira, em vez do front disparar N requisições (uma por
    // produto marcado) — bem mais rápido, e devolve um resumo em vez de deixar o
    // navegador tentar juntar N respostas separadas. Um id inválido no meio da lista não
    // derruba os outros: cai em "failed" e segue pros próximos, a transação só falha de
    // verdade se o GRUPO em si não existir (aí não tem em qual grupo adicionar nada).
    @Transactional
    public AddProductsToGroupResult addManyToGroup(Long groupId, List<Long> productIds) {
        ProductGroup group = productGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado: id " + groupId));

        int added = 0;
        List<Long> failed = new ArrayList<>();

        for (Long productId : productIds) {
            Product product = productRepository.findById(productId).orElse(null);
            if (product == null) {
                failed.add(productId);
                continue;
            }
            product.getGroups().add(group);
            productRepository.save(product);
            added++;
        }

        return new AddProductsToGroupResult(added, failed);
    }

    @Transactional
    public ProductResponse removeFromGroup(Long productId, Long groupId) {
        Product product = findEntityById(productId);
        product.getGroups().removeIf(group -> group.getId().equals(groupId));
        return toResponseWithoutPricing(productRepository.save(product));
    }

    private Map<Long, QuotationItem> loadLastWonBatch(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = products.stream().map(Product::getId).toList();
        List<QuotationItem> allWon = quotationItemRepository.findAllWonByProductIds(ids);

        Map<Long, QuotationItem> latestByProduct = new HashMap<>();
        for (QuotationItem item : allWon) {
            Long productId = item.getProduct().getId();
            QuotationItem current = latestByProduct.get(productId);
            if (current == null || item.getQuotation().getUpdatedAt().isAfter(current.getQuotation().getUpdatedAt())) {
                latestByProduct.put(productId, item);
            }
        }
        return latestByProduct;
    }

    private Product findEntityById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado: id " + id));
    }

    private ProductResponse toResponseWithoutPricing(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getBarcode(),
                product.getName(),
                product.getDescription(),
                product.getUnitOfMeasure(),
                null,
                null
        );
    }

    private ProductResponse toResponse(Product product, QuotationItem lastWonItem) {
        BigDecimal lastPrice = null;
        String lastSupplierName = null;

        if (lastWonItem != null) {
            Bid winningBid = lastWonItem.getWinningBid();
            lastPrice = winningBid.getValue();
            lastSupplierName = winningBid.getSupplier().getName();
        }

        return new ProductResponse(
                product.getId(),
                product.getBarcode(),
                product.getName(),
                product.getDescription(),
                product.getUnitOfMeasure(),
                lastPrice,
                lastSupplierName
        );
    }
}