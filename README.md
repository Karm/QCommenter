# QCommenter

This is a tiny service that posts comments on GitHub pull requests.

Fine-tuned GitHub Personal Access Token (PAT) that can only manipulate comments is stored in
a text file on the server as a triplet token:org:repo, so more tokens can be used for different orgs/repos.

The service crawls the open PRs, their finished workflows, downloads the logs, and posts them as comments.

