# QCommenter

This is a tiny service that posts comments on GitHub pull requests.

Fine-tuned GitHub Personal Access Token (PAT) that can only manipulate comments is stored in
an .env file on my server.

You get a toy token that allows you to post comments to a certain org/repo I can post too, e.g. quarkusio.

Server stores sha256 hashes of such tokens and their allowed org/repo pairs.

If the toy token leaks, the worst that can happen is a barrage of rouge comments appearing on a PR.

On the other hand, the PAT, despite being fine-tuned, also allows for *deleting* comments and that is bad...

A proper solution would be to use GitHub Apps, but this tiny thing does the job for now.

