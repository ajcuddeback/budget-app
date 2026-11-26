import 'package:budget_owl/features/categories/data/categories.dart';
import 'package:budget_owl/features/categories/domain/requests.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fixture_server.dart';

/// The bodies and the tree the categories screen builds — the web's (`category-patch.ts`,
/// `category-tree.ts`).
void main() {
  const bills = Category(id: 'cat-bills', name: 'Bills', kind: 'EXPENSE', archived: false);
  const phone = Category(
    id: 'cat-phone',
    name: 'Phone',
    kind: 'EXPENSE',
    archived: false,
    parentId: 'cat-bills',
    parentName: 'Bills',
    version: 4,
  );

  group('a new category', () {
    test('at the top level states its kind and no parent', () {
      expect(createCategoryBody(const CategoryForm(name: ' Pets ', kind: 'EXPENSE')), {
        'name': 'Pets',
        'kind': 'EXPENSE',
      });
    });

    test("under a parent names it and states no kind — it takes the parent's", () {
      expect(
        createCategoryBody(const CategoryForm(name: 'Vet', kind: 'INCOME', parentId: 'cat-pets')),
        {'name': 'Vet', 'parentId': 'cat-pets'},
      );
    });
  });

  group('an edit', () {
    test('untouched is the version alone, and is not sent', () {
      final patch = categoryPatch(
        phone,
        const CategoryForm(name: 'Phone', kind: 'EXPENSE', parentId: 'cat-bills'),
      );
      expect(patch, {'version': 4});
      expect(patchIsEmpty(patch), isTrue);
    });

    test('a rename keeps the parent by leaving parentId out', () {
      final patch = categoryPatch(
        phone,
        const CategoryForm(name: 'Mobile', kind: 'EXPENSE', parentId: 'cat-bills'),
      );
      expect(patch, {'version': 4, 'name': 'Mobile'});
      expect(patch.containsKey('parentId'), isFalse);
    });

    test('moving to the top level is an explicit null; moving under one is its id', () {
      final top = categoryPatch(phone, const CategoryForm(name: 'Phone', kind: 'EXPENSE'));
      expect(top.containsKey('parentId'), isTrue);
      expect(top['parentId'], isNull);
      expect(
        categoryPatch(
          phone,
          const CategoryForm(name: 'Phone', kind: 'EXPENSE', parentId: 'cat-home'),
        )['parentId'],
        'cat-home',
      );
    });

    test('never carries a kind: it is fixed at creation', () {
      final patch = categoryPatch(bills, const CategoryForm(name: 'Bills', kind: 'INCOME'));
      expect(patch.containsKey('kind'), isFalse);
    });
  });

  group('the tree', () {
    final all = [
      for (final c
          in (fixtureBody(
                    'categories',
                    state: 'categories',
                    path: '/api/categories?includeArchived=true&page=0&size=200',
                  )!
                  as Map<String, Object?>)['content']!
              as List<Object?>)
        Category.fromJson(c! as Map<String, Object?>),
    ];

    test('income before spending, parents by name, each followed by its own children', () {
      final groups = groupByKind(all.where((c) => !c.archived).toList());
      expect(groups.map((g) => g.key), ['INCOME', 'EXPENSE']);
      final spending = groups[1].value;
      expect(spending.first.parent.name, 'Bills');
      expect(spending.first.children.map((c) => c.name), [
        'Insurance',
        'Phone and internet',
        'Rent or mortgage',
        'Subscriptions',
        'Utilities',
      ]);
    });

    test('a child whose parent is not shown is promoted, not dropped', () {
      const orphan = Category(
        id: 'cat-orphan',
        name: 'Gifts',
        kind: 'EXPENSE',
        archived: false,
        parentId: 'cat-archived-parent',
      );
      final tree = buildCategoryTree([bills, phone, orphan]);
      expect(tree.map((b) => b.parent.name), ['Bills', 'Gifts']);
    });

    test('a parent may be any unarchived top-level category of the kind, but itself', () {
      final parents = possibleParents(all, 'EXPENSE', excluding: all.first.id);
      expect(
        parents.every((p) => p.parentId == null && !p.archived && p.kind == 'EXPENSE'),
        isTrue,
      );
      expect(parents.map((p) => p.name), isNot(contains('Wedding')));
      expect(possibleParents(all, 'INCOME').map((p) => p.name), ['Income']);
    });

    test('an unknown kind is a neutral word', () {
      expect(categoryKindKey('EXPENSE'), 'categories.kind.EXPENSE');
      expect(categoryKindKey('TRANSFER'), 'categories.kind.unknown');
    });
  });
}
