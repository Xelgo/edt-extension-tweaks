# AST Model And Procedure Formatting Investigation

This file is the new investigation log for building/editing BSL AST models and formatting procedure/function text.

It starts intentionally empty of historical conclusions because this is a new topic, separate from extension context, Query Constructor context, and build-freeze debugging.

## Goal

Understand how to safely generate, modify, format, and insert BSL procedure/function text using EDT/Xtext models instead of fragile string concatenation.

Potential scenarios:

- Build a BSL AST model for a generated procedure/function.
- Insert or replace procedure text in a module.
- Format generated code according to EDT/BSL formatter rules.
- Preserve existing comments, regions, annotations, directives, and line endings.
- Avoid breaking EDT derived state or validation caches.

## Working Hypotheses

- Prefer EDT/Xtext parser and formatter APIs over manual string templates where possible.
- Treat text generation and semantic model update as separate concerns:
  - parse/build valid BSL;
  - format;
  - insert into document/model;
  - let EDT reconcile.
- Do not mutate donor extension resources during generation for a consumer project.

## Questions To Answer

- Which EDT bundle owns the BSL parser and formatter APIs?
- Can we parse a standalone procedure/function fragment, or must we wrap it into a module?
- Which API returns a formatted text edit versus directly mutating an Xtext document?
- How does EDT preserve Russian keywords, comments, preprocessor directives, and regions?
- What is the safest way to apply generated text inside an open editor?
- What background jobs are triggered after applying generated BSL text?

## Test Matrix Draft

- Empty procedure without parameters.
- Procedure with export flag.
- Function with return value and default parameters.
- Server/client annotations.
- Preprocessor directives.
- Region/endregion blocks.
- Comments before and inside procedure.
- Existing module with same procedure name.
- Existing module with syntax errors nearby.
- External data processor module.
- Extension common module.

## Logging Rules

- Record exact EDT bundle/class names used for parser/formatter APIs.
- Record minimal input and output snippets.
- Record whether operation happened on text document, EMF model, or both.
- Record whether EDT validation markers changed after reconcile.
- Keep logs small and curated; do not paste full generated modules unless the full text is the actual failure case.
