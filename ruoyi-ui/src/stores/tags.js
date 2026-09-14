import { defineStore } from 'pinia';

export const useTagsStore = defineStore('tags', {
  state: () => ({ visited: [] }),
  actions: {
    visit(route) {
      if (!route.meta?.title || route.meta.public) return;
      const value = { path: route.fullPath, basePath: route.path, title: route.meta.title, affix: Boolean(route.meta.affix) };
      const index = this.visited.findIndex(item => item.basePath === value.basePath);
      if (index >= 0) this.visited[index] = value;
      else this.visited.push(value);
    },
    close(basePath) { this.visited = this.visited.filter(item => item.basePath !== basePath || item.affix); },
    closeOthers(basePath) { this.visited = this.visited.filter(item => item.affix || item.basePath === basePath); }
  }
});
