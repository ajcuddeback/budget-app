const path = require('path');
const router = require('express').Router();

router.get('/login', (req, res) => {
    res.sendFile(path.join(__dirname, '../../public/login.html'))
});

router.get('/signup', (req, res) => {
    res.sendFile(path.join(__dirname, '../../public/signup.html'))
});

router.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, '../../public/index.html'))
});

module.exports = router;