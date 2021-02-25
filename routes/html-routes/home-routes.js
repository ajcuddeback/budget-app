const path = require('path');
const router = require('express').Router();
const withAuth = require('../../utils/auth')

router.get('/login', (req, res) => {
    res.sendFile(path.join(__dirname, '../../public/login.html'))
});

router.get('/signup', (req, res) => {
    res.sendFile(path.join(__dirname, '../../public/signup.html'))
});

router.get('/:id', withAuth, (req, res) => {
    res.sendFile(path.join(__dirname, '../../public/home.html'))
});

module.exports = router;