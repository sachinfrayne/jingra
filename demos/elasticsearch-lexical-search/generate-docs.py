#!/usr/bin/env python3
"""Generate synthetic product catalog dataset for lexical search demo."""

import pandas as pd
import random

random.seed(42)

# Product categories
CATEGORIES = {
    "electronics": {
        "prefixes": ["Wireless", "Bluetooth", "Smart", "Digital", "Portable", "Premium", "Professional"],
        "products": ["Headphones", "Speaker", "Camera", "Laptop", "Tablet", "Smartphone", "Monitor"],
        "attributes": ["noise-cancelling", "HD", "4K", "waterproof", "gaming", "ultra-thin", "long-battery"]
    },
    "books": {
        "prefixes": ["Bestselling", "Award-winning", "Classic", "Modern", "Epic", "Thrilling"],
        "products": ["Novel", "Mystery", "Biography", "Cookbook", "Guide", "Textbook", "Memoir"],
        "attributes": ["hardcover", "illustrated", "bestseller", "limited-edition", "signed"]
    },
    "clothing": {
        "prefixes": ["Comfortable", "Stylish", "Premium", "Designer", "Casual", "Formal"],
        "products": ["Shirt", "Pants", "Jacket", "Dress", "Shoes", "Sweater", "Jeans"],
        "attributes": ["cotton", "waterproof", "breathable", "stretchable", "vintage", "slim-fit"]
    },
    "sports": {
        "prefixes": ["Professional", "Training", "Outdoor", "Indoor", "Competition", "Beginner"],
        "products": ["Ball", "Racket", "Mat", "Weights", "Shoes", "Gloves", "Helmet"],
        "attributes": ["lightweight", "durable", "non-slip", "adjustable", "weather-resistant"]
    },
    "home": {
        "prefixes": ["Modern", "Ergonomic", "Space-saving", "Luxury", "Compact", "Adjustable"],
        "products": ["Chair", "Desk", "Lamp", "Shelf", "Table", "Sofa", "Bed"],
        "attributes": ["wooden", "metal", "foldable", "reclining", "LED", "multi-purpose"]
    }
}

def generate_product(product_id):
    """Generate a single product document."""
    category = random.choice(list(CATEGORIES.keys()))
    cat_data = CATEGORIES[category]

    prefix = random.choice(cat_data["prefixes"])
    product = random.choice(cat_data["products"])
    attrs = random.sample(cat_data["attributes"], k=random.randint(2, 4))

    title = f"{prefix} {product}"

    # Simple descriptions without faker
    description_templates = [
        f"{title} - {', '.join(attrs)}. Perfect for everyday use.",
        f"{title} - {', '.join(attrs)}. High quality and durable.",
        f"{title} - {', '.join(attrs)}. Great value for money.",
        f"{title} - {', '.join(attrs)}. Recommended by experts.",
        f"{title} - {', '.join(attrs)}. Excellent choice for professionals.",
    ]
    description = random.choice(description_templates)

    # Add some variety with additional description sentences
    if random.random() > 0.5:
        extras = [
            " Free shipping available.",
            " Limited time offer.",
            " Customer favorite.",
            " Bestseller in its category.",
            " Award-winning design."
        ]
        description += random.choice(extras)

    return {
        "id": f"P{product_id:05d}",
        "title": title,
        "description": description,
        "category": category,
        "tags": attrs + [category, product.lower()]
    }

def main():
    num_docs = 1000
    batch_size = 10  # For perfect precision with recall@10
    print(f"Generating {num_docs} synthetic product documents in batches of {batch_size}...")

    docs = []
    for i in range(1, num_docs + 1):
        doc = generate_product(i)
        # Add batch_id for grouping (exactly 10 docs per batch)
        doc['batch_id'] = f"batch_{(i-1) // batch_size:03d}"
        docs.append(doc)

    df = pd.DataFrame(docs)
    output_path = "datasets/demo-data.parquet"
    df.to_parquet(output_path, index=False)

    print(f"✓ Created {output_path} with {len(df)} documents")
    print(f"\nSample documents:")
    for i in range(min(5, len(df))):
        doc = df.iloc[i]
        print(f"\n{i+1}. {doc['id']}")
        print(f"   Title: {doc['title']}")
        print(f"   Description: {doc['description'][:80]}...")
        print(f"   Category: {doc['category']}")
        print(f"   Tags: {doc['tags'][:3]}")

    print(f"\nCategory distribution:")
    print(df['category'].value_counts())

if __name__ == "__main__":
    main()
