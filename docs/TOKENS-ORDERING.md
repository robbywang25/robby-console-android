# Token account display order

The quota API keeps its existing wire arrays. Displayed account catalogs combine
`accounts`, `additionalAccounts` and optional `extendedAccounts`, keeping the
first occurrence of each valid source `id`. The original objects and arrays are
not mutated.

An account can carry this additive contract:

```json
{
  "displayOrder": {
    "version": 1,
    "providerKey": "google",
    "providerWeight": 600,
    "accountWeight": 100
  }
}
```

Weights are integers from 0 to 1,000,000; lower comes first. The provider key must
match the account's product/provider. Missing, unsupported or malformed metadata
uses the defaults below. The complete object is validated, so partially valid
metadata cannot change just one part of the order.

Accounts are grouped before sorting. Groups use their agreed provider weight,
then the canonical provider key in ordinal order. If a group's members disagree
(including a missing member using the default), that group uses its default
provider weight. Members then use account weight and their original source `id`
in ordinal order. Quota, freshness, name, email and locale never change ordering.

| Provider | Weight |
| --- | ---: |
| `openai` | 100 |
| `anthropic` | 200 |
| `xai` | 300 |
| `cursor` | 400 |
| `today` | 500 |
| `google` | 600 |
| `other` | 900 |

Default account weights are `codex-1`/`codex-2` 100/200;
`chatgpt-dots`/`chatgpt-chat` 300/400; `claude-1`/`claude-2` 100/200;
`grok`/`grok-bot` 100/200; `cursor` 100; `today-cn`/`today-global` 100/200;
`gemini-web`/`gemini-web-2` 100/200; `gemini-cli`/`gemini-cli-2` 300/400;
`gemini-api`/`gemini-api-2` 500/600. Unknown source IDs use 10,000.

`TokenAccountOrder` owns the Android implementation. The Token page, dashboard
card, account visibility settings and multi-account quota widget use it.
Calendar events still sort by event time; they are events rather than an account
catalog. Existing quota freshness, rendering and notification decisions remain
separate from account ordering.

The two-column Android quota widget starts a multi-account provider on a fresh
row when the previous provider leaves one cell. Reserved cells do not count as
shown accounts; the `+N` overflow count remains the number of hidden accounts.
