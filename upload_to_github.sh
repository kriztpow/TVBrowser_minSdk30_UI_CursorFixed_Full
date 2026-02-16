#!/data/data/com.termux/files/usr/bin/bash
echo "🚀 Subiendo proyecto a GitHube"
if ! command -v git &> /dev/null; then
  pkg update -y && pkg install git -y
fi
read -p "👤 Usuario Git: " GIT_NAME
git config --global user.name "$GIT_NAME"
read -p "📧 Email Git: " GIT_EMAIL
git config --global user.email "$GIT_EMAIL"
PROJECT_DIR=$(pwd)
git config --global --add safe.directory "$PROJECT_DIR"
[ ! -d ".git" ] && git init
read -p "🌐 URL del repo: " GITHUB_URL
git remote remove origin 2>/dev/null
git remote add origin "$GITHUB_URL"
[ ! -f ".gitignore" ] && cat <<EOL > .gitignore
.gradle/
/build/
/local.properties
*.keystore
.idea/
.vscode/
.DS_Store
app/build/
*.apk
EOL
find . -type f -size +100M > big_files.txt
if [ -s big_files.txt ]; then
  while IFS= read -r file; do
    echo "$file" >> .gitignore
    git rm --cached "$file" 2>/dev/null
  done < big_files.txt
  rm big_files.txt
fi
git add .
read -p "📝 Mensaje de commit: " COMMIT_MSG
[ -z "$COMMIT_MSG" ] && COMMIT_MSG="Subida inicial del TV Browser UI"
git commit -m "$COMMIT_MSG" || true
git branch -M main
git push -u origin main || true
echo "✅ Listo"
