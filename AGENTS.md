<!-- LOVABLE:BEGIN -->
> [!IMPORTANT]
> This project is connected to [Lovable](https://lovable.dev). Avoid rewriting
> published git history — force pushing, or rebasing/amending/squashing commits
> that are already pushed — as it rewrites history on Lovable's side and the
> user will likely lose their project history.
>
> Commits you push to the connected branch sync back to Lovable and show up in
> the editor, so keep the branch in a working state.
<!-- LOVABLE:END -->

- Keep VPN profile handling browser-only, storing only a non-sensitive summary in shared React context; profile contents are discarded after superficial validation and are never transmitted or persisted.
- Use separate TanStack leaf routes for LivreNet sections and a shared shell in the root route so navigation preserves session-only profile summaries.
- Keep visual roles and responsive patterns in src/styles.css and use existing design-system controls for consistent accessible interactions.
- Connectivity tests may only reach the app's own origin; never contact endpoints supplied in imported profiles because this web prototype cannot establish or measure VPN tunnels.
