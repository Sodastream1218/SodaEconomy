# Installing the SodaEconomy Maintainer Kit

Copy the files from this package into the root of the local SodaEconomy repository while preserving
the directory structure.

The kit adds only new files and does not replace existing issue forms, workflows, or the pull request
template.

After copying, run:

```powershell
git status
git add CONTRIBUTING.md CODE_OF_CONDUCT.md MAINTAINERS.md CHANGELOG.md .github/SECURITY.md .github/SUPPORT.md .github/FUNDING.yml .github/CODEOWNERS
git commit -m "Add project maintainer documentation"
git push
```

Then enable GitHub private vulnerability reporting:

1. Repository **Settings**
2. **Security** / **Code security and analysis**
3. Enable **Private vulnerability reporting**

`FUNDING.yml` contains comments only and can remain that way until a real funding platform is chosen.
