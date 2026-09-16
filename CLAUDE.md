# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Project instructions used to live entirely in this file. They've been split into `.claude/rules/` so
that only genuinely global context (build/run commands, the request-flow/API map) loads into every
session, while everything else (per-feature docs like rate limiting, Cassandra dual-write, the
Thymeleaf UI, Kafka, etc.) loads only when Claude is actually reading files in that part of the
codebase. See `.claude/rules/*.md` — each file's frontmatter (`paths:`, if present) shows what it's
scoped to; files with no frontmatter are global.
