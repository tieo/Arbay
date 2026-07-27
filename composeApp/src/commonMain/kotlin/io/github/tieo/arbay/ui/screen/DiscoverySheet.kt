package io.github.tieo.arbay.ui.screen

import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCatalog
import io.github.tieo.arbay.catalog.ProductCategory
import io.github.tieo.arbay.ui.AdaptiveSheet

internal sealed class Step {
    data object Categories : Step()
    data class Brands(val category: ProductCategory) : Step()
    data class Products(val category: ProductCategory, val brand: String?) : Step()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverySheet(
    initialCategory: ProductCategory? = null,
    onDismiss: () -> Unit,
    onProductSelected: (KnownProduct) -> Unit,
    onCustomSearch: (String) -> Unit,
    onLiveSearch: ((String) -> Unit)? = null,
    onFreeItems: (() -> Unit)? = null,
    onCarSearch: (() -> Unit)? = null,
) {
    var step by remember {
        mutableStateOf<Step>(
            if (initialCategory != null) {
                val brands = ProductCatalog.brandsFor(initialCategory)
                if (brands.size <= 3) Step.Products(initialCategory, null)
                else Step.Brands(initialCategory)
            } else {
                Step.Categories
            },
        )
    }
    var searchText by remember { mutableStateOf("") }
    var goingForward by remember { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }

    val searchResults = remember(searchText) {
        ProductCatalog.search(searchText)
    }

    AdaptiveSheet(onDismiss = onDismiss) {
        // BackHandler INSIDE the sheet so it intercepts before the sheet's own dismiss
        val canGoBack = step !is Step.Categories || searchText.isNotBlank()
        BackHandler(enabled = canGoBack) {
            goingForward = false
            if (searchText.isNotBlank()) {
                searchText = ""
            } else {
                when (val s = step) {
                    is Step.Products -> {
                        val brands = ProductCatalog.brandsFor(s.category)
                        step = if (brands.size <= 3) Step.Categories else Step.Brands(s.category)
                    }
                    is Step.Brands -> step = Step.Categories
                    is Step.Categories -> {}
                }
            }
        }

        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusRequester = remember { FocusRequester() }
        val scope = rememberCoroutineScope()
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

        // Auto-focus search bar after sheet animation settles
        LaunchedEffect(Unit) {
            delay(300)
            focusRequester.requestFocus()
        }

        fun navigateToStep(newStep: Step) {
            keyboardController?.hide()
            focusManager.clearFocus()
            goingForward = true
            if (imeVisible) {
                scope.launch {
                    delay(250)
                    step = newStep
                }
            } else {
                step = newStep
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            // Close button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close")
                }
            }

            // Search bar (always visible, auto-focused)
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Search products & marketplaces...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (searchText.isNotBlank() && onLiveSearch != null) {
                            onLiveSearch(searchText)
                        }
                    },
                ),
                leadingIcon = {
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { searchText = "" }) {
                            Icon(Icons.Default.Close, "Clear")
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )

            Spacer(Modifier.height(12.dp))

            // If searching, show flat results
            if (searchText.isNotBlank()) {
                // Live search button — always visible when there's text
                if (onLiveSearch != null) {
                    Button(
                        onClick = { onLiveSearch(searchText) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Search marketplaces for \"$searchText\"")
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (searchResults.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "No template matches for \"$searchText\"",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (onLiveSearch == null) {
                                Spacer(Modifier.height(12.dp))
                                FilledTonalButton(
                                    onClick = { onCustomSearch(searchText) },
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("Custom search for \"$searchText\"")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(searchResults, key = { it.displayName + it.mpn }) { product ->
                            ProductRow(product) {
                                onProductSelected(product)
                            }
                        }
                    }
                }
            } else {
                // Step-based browsing
                AnimatedContent(
                    targetState = step,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        if (goingForward) {
                            slideInHorizontally { it / 2 } + fadeIn() togetherWith
                                slideOutHorizontally { -it / 2 } + fadeOut()
                        } else {
                            slideInHorizontally { -it / 2 } + fadeIn() togetherWith
                                slideOutHorizontally { it / 2 } + fadeOut()
                        }
                    },
                ) { currentStep ->
                    when (currentStep) {
                        is Step.Categories -> CategoryGrid(
                            onCategorySelected = { cat ->
                                val brands = ProductCatalog.brandsFor(cat)
                                val target = if (brands.size <= 3) Step.Products(cat, null)
                                else Step.Brands(cat)
                                navigateToStep(target)
                            },
                            onCarSearch = onCarSearch?.let {
                                {
                                    onDismiss()
                                    it()
                                }
                            },
                            onCustomSearch = {
                                onCustomSearch("")
                            },
                            onSpecialTracking = {
                                if (onFreeItems != null) {
                                    onDismiss()
                                    onFreeItems()
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("Coming soon") }
                                }
                            },
                        )
                        is Step.Brands -> BrandList(
                            category = currentStep.category,
                            onBack = {
                                goingForward = false
                                step = Step.Categories
                            },
                            onBrandSelected = { brand ->
                                navigateToStep(Step.Products(currentStep.category, brand))
                            },
                            onAllProducts = {
                                navigateToStep(Step.Products(currentStep.category, null))
                            },
                            onCustomSearch = {
                                onCustomSearch(currentStep.category.displayName)
                            },
                        )
                        is Step.Products -> ProductList(
                            category = currentStep.category,
                            brand = currentStep.brand,
                            onBack = {
                                goingForward = false
                                val brands = ProductCatalog.brandsFor(currentStep.category)
                                step = if (brands.size <= 3) Step.Categories
                                else Step.Brands(currentStep.category)
                            },
                            onProductSelected = { product ->
                                onProductSelected(product)
                            },
                            onCustomSearch = {
                                onCustomSearch(currentStep.category.displayName)
                            },
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
        }
    }
}

@Composable
internal fun CategoryGrid(
    onCategorySelected: (ProductCategory) -> Unit,
    onCustomSearch: () -> Unit,
    onSpecialTracking: () -> Unit,
    onCarSearch: (() -> Unit)? = null,
) {
    Column {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            // Car search hero — full width, first, the structured cross-border vehicle search
            if (onCarSearch != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CarSearchHeroTile(onClick = onCarSearch)
                }
            }

            // Custom search tile — neutral surface, accent icon (one accent app-wide).
            item {
                DiscoveryTile(
                    icon = Icons.Default.Edit,
                    title = "Custom search",
                    subtitle = "Your own query",
                    onClick = onCustomSearch,
                )
            }

            // Free items tile
            item {
                DiscoveryTile(
                    icon = Icons.Default.AutoAwesome,
                    title = "Free Items",
                    subtitle = "Given away nearby",
                    onClick = onSpecialTracking,
                )
            }

            // Category tiles — Cars handled by the hero tile above when car search is wired
            items(ProductCategory.entries.filter { onCarSearch == null || it != ProductCategory.CARS }) { category ->
                DiscoveryTile(
                    icon = category.icon,
                    title = category.displayName,
                    subtitle = "${ProductCatalog.productsFor(category).size} presets",
                    onClick = { onCategorySelected(category) },
                )
            }
        }
    }
}

/** One square entry tile in the discovery grid: neutral surface, accent-tinted icon, title and a
 *  muted subtitle. Every tile shares this so the grid reads as one coordinated set, distinguished
 *  by icon and label rather than by competing background colours. */
@Composable
private fun DiscoveryTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Prominent, full-width entry to the structured car search. Sits at the top of the
 *  category grid because a car buyer reasons in filters (year, km, price, power), not
 *  in preset product tiles. */
@Composable
private fun CarSearchHeroTile(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.DirectionsCar, null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Car search",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Make, year, mileage, price, power, gearbox across EU markets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
            Icon(
                Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
internal fun BrandList(
    category: ProductCategory,
    onBack: () -> Unit,
    onBrandSelected: (String) -> Unit,
    onAllProducts: () -> Unit,
    onCustomSearch: () -> Unit,
) {
    val brands = remember(category) { ProductCatalog.brandsFor(category) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Column {
                Text(
                    category.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Pick a brand",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // "All" option
            item {
                Card(
                    onClick = onAllProducts,
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            category.icon,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "All ${category.displayName}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${ProductCatalog.productsFor(category).size} presets",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            items(brands) { brand ->
                val count = ProductCatalog.productsFor(category, brand).size
                Card(
                    onClick = { onBrandSelected(brand) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            brand,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                "$count",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Card(
                    onClick = onCustomSearch,
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Edit, null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Not listed? Use a custom search",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProductList(
    category: ProductCategory,
    brand: String?,
    onBack: () -> Unit,
    onProductSelected: (KnownProduct) -> Unit,
    onCustomSearch: () -> Unit,
) {
    val products = remember(category, brand) {
        ProductCatalog.productsFor(category, brand)
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Column {
                Text(
                    buildString {
                        append(category.displayName)
                        if (brand != null) append(" > $brand")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${products.size} presets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(products, key = { it.displayName + it.mpn }) { product ->
                ProductRow(product) { onProductSelected(product) }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Card(
                    onClick = onCustomSearch,
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Edit, null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Can't find it? Custom search",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProductRow(
    product: KnownProduct,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        product.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        product.searchQuery,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Icon(
                    Icons.Default.Add,
                    "Track",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (product.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    product.mpn?.let { mpn ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                mpn,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    product.tags.take(4).forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
