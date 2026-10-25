import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('api_docs', Path(__file__).with_name('build-api-reference.py'))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class ApiReferenceTest(unittest.TestCase):
    def test_code_is_text_not_executable_html(self):
        result = module.markdown(['```js', '<script>throw Error("x")</script>', '```'])
        self.assertNotIn('<script>', result)
        self.assertIn('&lt;script&gt;', result)

    def test_signatures_with_union_types_remain_one_cell(self):
        self.assertEqual(['`color: string | number`', '颜色'], module.table_cells('| `color: string | number` | 颜色 |'))

    def test_parameter_tables_and_fences_have_stable_markup(self):
        value = module.markdown(['| 参数 | 返回 |', '| --- | --- |', '| `path` | `string` |', '', '```js', 'files.read(path);', '```'])
        self.assertEqual(1, value.count('<table>'))
        self.assertEqual(1, value.count('<pre>'))
        self.assertNotIn('---', value)

    def test_legacy_extraction_excludes_scripts_and_handlers(self):
        parser = module.LegacySections('files.html')
        parser.feed('<h2>files.read(path)<a id="read">#</a></h2><p onclick="run()">读取</p>'
                    '<script>danger()</script><a href="javascript:run()">bad</a><h2>files.write(path)</h2><p>写入文件</p>')
        parser.flush()
        self.assertEqual('read', parser.rows[0][2])
        self.assertNotIn('danger', parser.rows[0][1])
        self.assertNotIn('onclick', parser.rows[0][1])
        self.assertNotIn('javascript:', parser.rows[0][1])

    def test_local_cross_references_keep_the_source_chapter(self):
        parser = module.LegacySections('files.html'); parser.feed('<h2>files.read</h2><p><a href="#write">写</a></p>'); parser.flush()
        self.assertIn('href="files.html#write"', parser.rows[0][1])

    def test_unclosed_examples_fail_generation(self):
        with self.assertRaises(ValueError): module.markdown(['```js', 'missing end'])

if __name__ == '__main__': unittest.main()
