# Universal Agent Evolution Contract
Lifecycle: SEED -> DISCOVER -> BRANCH -> IMPLEMENT -> TEST -> SECURITY -> REGRESSION -> PROMOTE -> OBSERVE -> ROLLBACK.
Every material change must identify its parent node, capability, preconditions, activation path, self-test, observability, security scope and rollback.
Execution plane and candidate evolution plane remain separate. Unvalidated candidate code must not replace production runtime.
Provider credentials are environment-only; no secrets in source or Git history.
